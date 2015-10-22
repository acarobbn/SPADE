package spade.storage;

import java.io.FileWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.util.FileManager;

import spade.core.AbstractEdge;
import spade.core.AbstractStorage;
import spade.core.AbstractVertex;
import spade.edge.prov.Used;
import spade.edge.prov.WasAssociatedWith;
import spade.edge.prov.WasDerivedFrom;
import spade.edge.prov.WasGeneratedBy;
import spade.edge.prov.WasInformedBy;
import spade.vertex.prov.Activity;
import spade.vertex.prov.Agent;
import spade.vertex.prov.Entity;

public class Prov extends AbstractStorage{

    public Logger logger = Logger.getLogger(this.getClass().getName());
	
    public static enum ProvFormat { PROVO, PROVN }

    private ProvFormat provOutputFormat;
	
    private FileWriter outputFile;
    private final int TRANSACTION_LIMIT = 1000;
    private int transaction_count;
    private String filePath;
    
    private final String provNamespacePrefix = "prov";
    private final String provNamespaceURI = "http://www.w3.org/ns/prov#";
    
    private final String defaultNamespacePrefix = "data";
    private final String defaultNamespaceURI 	= "http://spade.csl.sri.com/#";
    
    private Map<String, Set<String>> annotationToNamespaceMap = new HashMap<String, Set<String>>();
    private Map<String, String> namespacePrefixToURIMap = new HashMap<String, String>();
    
    private final String OUTFILE_KEY = "output";
    
    private final String TAB = "\t", NEWLINE = "\n";
    
    protected Pattern pattern_key_value = Pattern.compile("(\\w+)=\"*((?<=\")[^\"]+(?=\")|([^\\s]+))\"*");
    protected Map<String, String> parseKeyValPairs(String arguments) {
        Matcher key_value_matcher = pattern_key_value.matcher(arguments);
        Map<String, String> keyValPairs = new HashMap<String, String>();
        while (key_value_matcher.find()) {
            keyValPairs.put(key_value_matcher.group(1).trim(), key_value_matcher.group(2).trim());
        }
        return keyValPairs;
    }
    
    private final Map<String, String> provOStringFormatsForEdgeTypes = new HashMap<String, String>(){
		{
			put("spade.edge.prov.Used", "%s:%s %s:qualifiedUsage [\n\ta %s:Usage;\n\t%s:entity %s:%s;\n%s]; .\n\n");
			put("spade.edge.prov.WasAssociatedWith", "%s:%s %s:qualifiedAssociation [\n\ta %s:Association;\n\t%s:agent %s:%s;\n%s]; .\n\n");
			put("spade.edge.prov.WasDerivedFrom", "%s:%s %s:qualifiedDerivation [\n\ta %s:Derivation;\n\t%s:entity %s:%s;\n%s]; .\n\n");
			put("spade.edge.prov.WasGeneratedBy", "%s:%s %s:qualifiedGeneration [\n\ta %s:Generation;\n\t%s:activity %s:%s;\n%s]; .\n\n");
			put("spade.edge.prov.WasInformedBy", "%s:%s %s:qualifiedCommunication [\n\ta %s:Communication;\n\t%s:activity %s:%s;\n%s]; .\n\n");
		}
	};
	
	private final Map<String, String> provNStringFormatsForEdgeTypes = new HashMap<String, String>(){
		{
			put("spade.edge.prov.Used", "\tused(%s:%s,%s:%s, - ,%s)\n");
			put("spade.edge.prov.WasAssociatedWith", "\twasAssociatedWith(%s:%s,%s:%s, - ,%s)\n");
			put("spade.edge.prov.WasDerivedFrom", "\twasDerivedFrom(%s:%s,%s:%s,%s)\n");
			put("spade.edge.prov.WasGeneratedBy", "\twasGeneratedBy(%s:%s,%s:%s, - ,%s)\n");
			put("spade.edge.prov.WasInformedBy", "\twasInformedBy(%s:%s,%s:%s,%s)\n");
		}
	};

	private final String provOStringFormatForVertex = "%s:%s\n\ta %s:%s;\n%s .\n\n";
	private final String provNStringFormatForVertex = "\t%s(%s:%s,%s)\n";
	
    @Override
	public boolean initialize(String arguments) {
		Map<String, String> args = parseKeyValPairs(arguments);
		
		Map<String, String> nsPrefixToFileMap = new HashMap<String, String>();
		nsPrefixToFileMap.putAll(args);
		nsPrefixToFileMap.remove(OUTFILE_KEY);
		if(!nsPrefixToFileMap.containsKey(provNamespacePrefix) && !nsPrefixToFileMap.containsKey(defaultNamespacePrefix)){ //i.e. this prefix is reserved
			if(loadAnnotationsFromRDFs(nsPrefixToFileMap)){
				filePath = args.get(OUTFILE_KEY);
				provOutputFormat = getProvFormatByFileExt(filePath);
				if(provOutputFormat == null){
					if(args.get(OUTFILE_KEY) == null){
						logger.log(Level.SEVERE, "No output file specified.");
					}else{
						logger.log(Level.SEVERE, "Invalid file extension. Can only be 'provn' or 'ttl'.");
					}
					return false;
				}else{
					try {
			            outputFile = new FileWriter(filePath, false);
			            transaction_count = 0;
			            switch (provOutputFormat) {
							case PROVN:
								outputFile.write("document\n");	            
					            for(String nsPrefix : namespacePrefixToURIMap.keySet()){
					            	outputFile.write(TAB + "prefix "+nsPrefix+" <"+namespacePrefixToURIMap.get(nsPrefix)+">\n");
					            }
					            outputFile.write(TAB + "prefix "+defaultNamespacePrefix+" <"+defaultNamespaceURI+">\n");
					            outputFile.write(NEWLINE);
								break;
							case PROVO:
								for(String nsPrefix : namespacePrefixToURIMap.keySet()){
					            	outputFile.write("@prefix "+nsPrefix+": <"+namespacePrefixToURIMap.get(nsPrefix)+"> .\n");
					            }
					            outputFile.write("@prefix "+defaultNamespacePrefix+": <"+defaultNamespaceURI+"> .\n");
					            outputFile.write("@prefix "+provNamespacePrefix+": <"+provNamespaceURI+"> .\n");
					            outputFile.write(NEWLINE);
								break;
							default:
								break;
			            }
			            return true;
			        } catch (Exception exception) {
			            logger.log(Level.SEVERE, "Error while writing to file", exception);
			            return false;
			        }
				}
			}else{
				return false;
			}		
		}else{
			logger.log(Level.SEVERE, "The namespace prefixes '"+provNamespacePrefix+"' and '"+defaultNamespacePrefix+"' are reserved");
			return false;
		}
	}

    private void checkTransactions() {
        transaction_count++;
        if (transaction_count == TRANSACTION_LIMIT) {
            try {
                outputFile.flush();
                outputFile.close();
                outputFile = new FileWriter(filePath, true);
                transaction_count = 0;
            } catch (Exception exception) {
                logger.log(Level.SEVERE, null, exception);
            }
        }
    }

    @Override
    public boolean shutdown() {
        try {
        	switch (provOutputFormat) {
        		case PROVO:
        		//nothing
        			break;
				case PROVN:
					outputFile.write(
		            		"\nendDocument\n"
		                    );
					break;
				default:
					break;
			}
            outputFile.close();
            return true;
        } catch (Exception exception) {
            logger.log(Level.SEVERE, null, exception);
            return false;
        }
    }

	@Override
	public boolean putVertex(AbstractVertex incomingVertex) {
		try{
			String serializedVertex = getSerializedVertex(incomingVertex);
			outputFile.write(serializedVertex);
			checkTransactions();
			//vertexCount++; finalcommitfilter is doing this increment already
			return true;
		}catch(Exception e){
			logger.log(Level.WARNING, null, e);
			return false;
		}
	}

	@Override
	public boolean putEdge(AbstractEdge incomingEdge) {
		try{
			String serializedEdge = getSerializedEdge(incomingEdge);
			outputFile.write(serializedEdge);
			checkTransactions();
			//edgeCount++; finalcommitfilter is doing this increment already
			return true;
		}catch(Exception e){
			logger.log(Level.WARNING, null, e);
			return false;
		}
	}
	
	public ProvFormat getProvFormatByFileExt(String filepath){
		filepath = String.valueOf(filepath).trim().toLowerCase();
		if(filepath.endsWith(".ttl")){
			return ProvFormat.PROVO;
		}else if(filepath.endsWith(".provn")){
			return ProvFormat.PROVN;
		}
		return null;
	}
	
	public String getSerializedVertex(AbstractVertex vertex){
		String vertexString = null;
		switch (provOutputFormat) {
			case PROVO:
				
				vertexString = String.format(provOStringFormatForVertex, 
													defaultNamespacePrefix, 
													DigestUtils.sha256Hex(vertex.toString()), 
													provNamespacePrefix, 
													vertex.getClass().getSimpleName(), 
													getProvOFormattedKeyValPair(vertex.getAnnotations()));
								
				break;
			case PROVN:

				vertexString = String.format(provNStringFormatForVertex, 
													vertex.getClass().getSimpleName().toLowerCase(),
													defaultNamespacePrefix,
													DigestUtils.sha256Hex(vertex.toString()),
													getProvNFormattedKeyValPair(vertex.getAnnotations()));
				
				break;
			default:
				break;
		}
		return vertexString;
	}
	
	public String getSerializedEdge(AbstractEdge edge){
		String srcVertexKey = DigestUtils.sha256Hex(edge.getSourceVertex().toString());
		String destVertexKey = DigestUtils.sha256Hex(edge.getDestinationVertex().toString());
		String edgeString = null;
		switch (provOutputFormat) {
			case PROVO:
				edgeString = String.format(provOStringFormatsForEdgeTypes.get(edge.getClass().getName()),
													defaultNamespacePrefix,
													srcVertexKey,
													provNamespacePrefix,
													provNamespacePrefix,
													provNamespacePrefix,
													defaultNamespacePrefix,
													destVertexKey,
													getProvOFormattedKeyValPair(edge.getAnnotations()));
				
				break;
			case PROVN:
				edgeString = String.format(provNStringFormatsForEdgeTypes.get(edge.getClass().getName()), 
													defaultNamespacePrefix, 
													srcVertexKey, 
													defaultNamespacePrefix, 
													destVertexKey,
													getProvNFormattedKeyValPair(edge.getAnnotations()));
				break;
			default:
				break;
		}
		return edgeString;
	}
	
	private String getProvNFormattedKeyValPair(Map<String, String> keyvals){
		StringBuffer string = new StringBuffer();
		string.append("[ ");
		for(String key : keyvals.keySet()){
			String value = keyvals.get(key);
			string.append(getNSPrefixForAnnotation(key)).append(":").append(key).append("=\"").append(value).append("\",");
		}
		string.deleteCharAt(string.length() - 1);
		string.append("]");
		return string.toString();
	}
	
	private String getProvOFormattedKeyValPair(Map<String, String> keyvals){
		StringBuffer annotationsString = new StringBuffer();
		if(keyvals.size() > 0){
			for(Map.Entry<String, String> currentEntry : keyvals.entrySet()){
				annotationsString.append(TAB).append(getNSPrefixForAnnotation(currentEntry.getKey())).append(":").append(currentEntry.getKey()).append(" \"").append(currentEntry.getValue()).append("\";").append(NEWLINE);
			}
		}
		return annotationsString.toString();
	}
	
	private String getNSPrefixForAnnotation(String annotation){
		if(annotationToNamespaceMap.get(annotation) != null && annotationToNamespaceMap.get(annotation).size() > 0){
			return annotationToNamespaceMap.get(annotation).iterator().next();
		}
		logger.log(Level.WARNING, "The annotation '"+annotation+"' doesn't exist in any of the namespaces provided");
		return defaultNamespacePrefix;
	}

	
	private boolean loadAnnotationsFromRDFs(Map<String, String> nsPrefixToFileMap){
		
		for(String nsprefix : nsPrefixToFileMap.keySet()){
			
			String rdfFile = nsPrefixToFileMap.get(nsprefix);
						
			Model model = null;
			try{
				model = FileManager.get().loadModel(rdfFile);
				
				StmtIterator stmtIterator = model.listStatements();
				
				while(stmtIterator.hasNext()){
					Statement statement = stmtIterator.nextStatement();
					
					if(statement.getPredicate().getLocalName().equals("type") &&
							statement.getPredicate().getNameSpace().contains("http://www.w3.org/1999/02/22-rdf-syntax-ns") &&
							(statement.getObject().asResource().getLocalName().equals("Property") &&
							statement.getObject().asResource().getNameSpace().contains("http://www.w3.org/2000/01/rdf-schema") ||
							statement.getObject().asResource().getLocalName().equals("Property") &&
							statement.getObject().asResource().getNameSpace().contains("http://www.w3.org/1999/02/22-rdf-syntax-ns"))){
						if(!(statement.getSubject().getLocalName() == null || statement.getSubject().getNameSpace() == null)){
							Set<String> nsSet = null;
							if((nsSet = annotationToNamespaceMap.get(statement.getSubject().getLocalName())) == null){
								nsSet = new HashSet<String>();
								annotationToNamespaceMap.put(statement.getSubject().getLocalName(), nsSet);
							}
							nsSet.add(nsprefix);
							namespacePrefixToURIMap.put(nsprefix, statement.getSubject().getNameSpace());
						}					
					}				
				}			
				
				model.close();
			}catch(Exception exception){
				logger.log(Level.SEVERE, "Failed to read file '"+rdfFile+"'", exception);
				return false;
			}				
			
		}
		
		for(String annotation : annotationToNamespaceMap.keySet()){
			if(annotationToNamespaceMap.get(annotation).size() > 1){
				List<String> filepaths = new ArrayList<String>();
				for(String nsPrefix : annotationToNamespaceMap.get(annotation)){
					filepaths.add(nsPrefixToFileMap.get(nsPrefix));
				}
				logger.log(Level.WARNING, "Files " + filepaths + " all have the property with name '"+annotation+"'");
			}
		}
		
		return true;
				
	}
	
	public static void main(String [] args) throws Exception{
		Activity a = new Activity();
		a.addAnnotation("name", "a1");
		Activity b = new Activity();
		b.addAnnotation("name", "a2");
		WasInformedBy e = new WasInformedBy(b, a);
		e.addAnnotation("operation", "forked");
		Entity f1 = new Entity();
		f1.addAnnotation("filename", "file_f1");
		Entity f2 = new Entity();
		f2.addAnnotation("filename", "file_f2");
		WasGeneratedBy e2 = new WasGeneratedBy(f1, a);
		e2.addAnnotation("operation", "write");
		WasDerivedFrom e3 = new WasDerivedFrom(f2, f1);
		e3.addAnnotation("operation", "rename");
		Agent agent = new Agent();
		agent.addAnnotation("user", "spade");
		WasAssociatedWith e4 = new WasAssociatedWith(a, agent);
		e4.addAnnotation("test", "anno");
		Used e5 = new Used(b, f2);
		e5.addAnnotation("operation", "read");
				
		Prov ttl = new Prov();
		ttl.initialize("output=/home/ubwork/prov.ttl audit=/home/ubwork/Desktop/audit.rdfs");
		Prov provn = new Prov();
		provn.initialize("output=/home/ubwork/prov.provn audit=/home/ubwork/Desktop/audit.rdfs");
		
		Prov provs[] = new Prov[]{ttl, provn};
		
		for(int cc = 0; cc<provs.length; cc++){
			Prov prov = provs[cc];
			prov.putVertex(a);
			prov.putVertex(b);
			prov.putVertex(f1);
			prov.putVertex(f2);
			prov.putVertex(agent);
			prov.putEdge(e);
			prov.putEdge(e2);
			prov.putEdge(e3);
			prov.putEdge(e4);
			prov.putEdge(e5);
			prov.shutdown();
		}
	}
}
