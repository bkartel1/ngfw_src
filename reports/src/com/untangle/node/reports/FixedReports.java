/**
 * $Id: ReportsHandler.java,v 1.00 2015/11/25 11:56:09 cblaise Exp $
 */
package com.untangle.node.reports;

import org.apache.log4j.Logger;

import com.untangle.uvm.MailSender;
import com.untangle.uvm.UvmContext;
import com.untangle.uvm.UvmContextFactory;
import com.untangle.uvm.util.I18nUtil;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.IOException;

import java.nio.file.Files;
import java.nio.file.Path;

import java.text.DateFormat;
import java.text.SimpleDateFormat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import java.util.Map;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.lang.Integer;
import java.lang.reflect.Method;
import java.util.Calendar;
import java.util.Date;
import java.util.Random;

import org.json.JSONObject;

/**
 * Generate fixed reports from django-like templates
 */
@SuppressWarnings("serial")

public class FixedReports
{
    private static final Logger logger = Logger.getLogger( AlertHandler.class );

    public static final String REPORTS_FIXED_TEMPLATE_FILENAME =  System.getProperty("uvm.lib.dir") + "/untangle-node-reports/templates/reports.html";
        
    private StringBuilder messageText = null;
    private StringBuilder messageHtml = null;
    private List<Map<MailSender.MessagePartsField,String>> messageParts;

    I18nUtil i18nUtil = null;
    private static final DateFormat dateFormatter = new SimpleDateFormat("yyyy M d");

    public enum Tag {
        _SYSTEM,
        VARIABLE,
        TRANS,
        FOR,
        ENDFOR,
        IF,
        ELSE,
        ENDIF,
        WITH,
        ENDWITH
    }

    public enum Filter{
        FORMAT
    }

    private static final Map<Tag, Pattern> TagPatterns;
    private static final Map<Filter, Pattern> FilterPatterns;
    private static final Pattern NonGreedyVariablePattern;

    static {
       TagPatterns = new HashMap<Tag, Pattern>();

        TagPatterns.put(Tag.VARIABLE, Pattern.compile("\\{\\{\\s*(.+)\\s*\\}\\}"));
        TagPatterns.put(Tag.TRANS, Pattern.compile("\\{\\%\\s*trans \"([^\"]+)\"\\s*\\%\\}"));
        TagPatterns.put(Tag.FOR, Pattern.compile("\\{\\%\\s*for (.+?) in (.+?)\\s*\\%\\}"));
        TagPatterns.put(Tag.ENDFOR, Pattern.compile("\\{\\%\\s*endfor\\s*\\%\\}"));
        TagPatterns.put(Tag.IF, Pattern.compile("\\{\\%\\s*if (.+?)\\s*\\%\\}"));
        TagPatterns.put(Tag.ELSE, Pattern.compile("\\{\\%\\s*else\\s*\\%\\}"));
        TagPatterns.put(Tag.ENDIF, Pattern.compile("\\{\\%\\s*endif\\s*\\%\\}"));
        TagPatterns.put(Tag.WITH, Pattern.compile("\\{\\%\\s*with (.+?)\\=(.+?)\\s*\\%\\}"));
        TagPatterns.put(Tag.ENDWITH, Pattern.compile("\\{\\%\\s*endwith\\s*\\%\\}"));

        FilterPatterns = new HashMap<Filter, Pattern>();
        FilterPatterns.put(Filter.FORMAT, Pattern.compile("format\\=(.+)"));

        NonGreedyVariablePattern = Pattern.compile("\\{\\{\\s*(.+?)\\s*\\}\\}");
    }

    private static final Pattern Conditional = Pattern.compile("(.+?)\\s*(\\=\\=|\\!\\=)\\s*(.+)");

    /*
     */
    class variableContext
    {
        Tag tag;
        Object object;
        String name;
        int index;

        public variableContext(Tag tag, String name, Object object)
        {
            this.tag = tag;
            this.name = name;
            this.object = object;
            this.index = -1;
        }
    }

    /*
     */
    class parseContext
    {
        Boolean allowOutput = true;
        Boolean ignoreLine = true;
        Boolean buildLoopBuffer = false;

        int loopsSeen = 0;
        StringBuilder loopBuffer = null;

        Object variableObject;
        String variableName;
        int variableIndex;

        public parseContext()
        {
            loopBuffer = new StringBuilder();
        }

        List<variableContext> variables = new ArrayList<variableContext>();

        public void addVariable(Tag tag, String name, Object object){
            variables.add(new variableContext(tag, name, object));
        }
        public void removeVariable(Tag tag){
            for(variableContext vc : variables){
                if(vc.tag == tag){
                    variables.remove(vc);
                    break;
                }
            }
        }
        public variableContext getVariableContext(Tag tag){
            for(variableContext vc: variables){
                if(vc.tag.equals(tag)){
                    return vc;
                }
            }
            return null;
        }
        public Object getVariable(String name){
            for(variableContext vc: variables){
                if(vc.name.equals(name)){
                    if(vc.index != -1){
                        return ((List) vc.object).get(vc.index);
                    }else{
                        return vc.object;
                    }
                }
            }
            return null;
        }

        public void setVariable(String name, Object obj)
        {
            variableName = name;
            variableObject = obj;
            variableIndex = 0;
        }
        public void unsetVariable()
        {
            variableName = null;
            variableObject = null;
        }
    }
    private List<parseContext> parseContextStack;

    /*
     */
    class selector
    {
        List<String> fields = null;
        List<String> arguments = null;
        List<String> filters = null;
        String selectorString = null;

        /*
         Selector is formatted like fields[,arguments][|filters]
         */
        public selector(String selectorString)
        {
            this.selectorString = selectorString;
            /*
             * Parse variables
             */
            Matcher tag = NonGreedyVariablePattern.matcher(selectorString);
            while( tag.find()){
                Object variable = getVariable(new selector(tag.group(1)));
                selectorString = 
                    selectorString.substring(0,selectorString.indexOf(tag.group())) + 
                    variable.toString() +
                    selectorString.substring(selectorString.indexOf(tag.group()) + tag.group().length());
            }

            filters = new ArrayList<String>(Arrays.asList(selectorString.split("\\|")));
            arguments = new ArrayList<String>(Arrays.asList(filters.get(0).split("\\,")));
            fields = new ArrayList<String>(Arrays.asList(arguments.get(0).split("\\.")));
            filters.remove(0);
            arguments.remove(0);
        }

        public String toString(){
            return selectorString;
        }

    }

    /*
     */
    public void send(ArrayList<String> recipientsList, String reportsUrl)
    {
        File fixedReportTemplateFile = new File(REPORTS_FIXED_TEMPLATE_FILENAME);
        Map<String, String> i18nMap = UvmContextFactory.context().languageManager().getTranslations("untangle");
        i18nUtil = new I18nUtil(i18nMap);

        parseContextStack = new ArrayList<parseContext>();
        parseContext context = new parseContext();
        parseContextStack.add(context);

        messageParts = new ArrayList<Map<MailSender.MessagePartsField,String>>();

        Calendar c = Calendar.getInstance();
        c.add(Calendar.DATE, - 1);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        Date startDate = c.getTime();
        c.add(Calendar.DAY_OF_MONTH, 1);
        Date endDate = c.getTime();

        context.addVariable(Tag._SYSTEM, "startDate", startDate);
        context.addVariable(Tag._SYSTEM, "endDate", endDate);
        context.addVariable(Tag._SYSTEM, "title", I18nUtil.marktr("Daily Report") + ": " + dateFormatter.format(startDate));
        context.addVariable(Tag._SYSTEM, "url", reportsUrl);

        messageText = new StringBuilder();
        messageHtml = new StringBuilder();

        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(fixedReportTemplateFile), "UTF-8"));

            messageText.append(I18nUtil.marktr("HTML Report enclosed.") + "\n\n");

            String line;
            while ((line = reader.readLine()) != null) {
                parse(line);
            }
            messageHtml.append("\n\n");

        } catch (IOException e) {
            logger.warn("IOException: ",e);
        } finally {
            try {
                if(reader != null) {
                    reader.close();
                }
            } catch (Exception e) {
                logger.warn("cannot close");

            }
        }

        String[] recipients = new String[recipientsList.size()];
        recipients = recipientsList.toArray(recipients);

        String subject = getVariable(new selector("title")).toString() + " - " + UvmContextFactory.context().networkManager().getNetworkSettings().getHostName();

        Map<MailSender.MessagePartsField,String> part = new HashMap<MailSender.MessagePartsField,String>();
        part.put(MailSender.MessagePartsField.TEXT, messageText.toString());
        messageParts.add(part);
        part = new HashMap<MailSender.MessagePartsField,String>();
        part.put(MailSender.MessagePartsField.HTML, messageHtml.toString());
        messageParts.add(part);
        UvmContextFactory.context().mailSender().sendMessage(recipients, subject, messageParts);

    }

    /*
     */
    void parse(String buffer){
        int contextIndex = parseContextStack.size() - 1;

        parseContext parseContext = parseContextStack.get(contextIndex);

        Matcher tag;
        String search = null;
        String replace = null;
        for(String line : buffer.split("\\n")){
            parseContext.ignoreLine = false;

            /*
             * Perform translations
             */
            for(Map.Entry<Tag, Pattern> syntax : TagPatterns.entrySet()) {
                if(syntax.getKey() == Tag.TRANS){
                    tag = syntax.getValue().matcher(line);
                    while(tag.find()){
                        StringBuilder newLine = new StringBuilder();
                        newLine.append(line.substring(0,line.indexOf(tag.group())));
                        newLine.append(i18nUtil.tr(tag.group(1).trim()));
                        newLine.append(line.substring(line.indexOf(tag.group()) + tag.group().length()) + "\n");
                        line = newLine.toString();
                    }
                }
            }

            /*
             * Parse syntax
             */
            for(Map.Entry<Tag, Pattern> syntax : TagPatterns.entrySet()) {
                try{
                    tag = syntax.getValue().matcher(line);
                    while( tag.find()){
                        switch(syntax.getKey()){
                            case VARIABLE:
                                if(parseContext.allowOutput && 
                                    parseContext.ignoreLine == false && 
                                    parseContext.buildLoopBuffer == false){
                                    try{
                                        messageHtml.append(line.substring(0,line.indexOf(tag.group())));
                                        insertVariable(line, new selector(tag.group(1).trim()));
                                        messageHtml.append(line.substring(line.indexOf(tag.group()) + tag.group().length()) + "\n");
                                    }catch(Exception e){
                                        logger.warn("Unable to insert variable :" + e );
                                    }
                                    parseContext.ignoreLine = true;
                                }
                                break;
                            case FOR:
                                if( parseContext.buildLoopBuffer == true){
                                    parseContext.loopsSeen++;
                                }else{
                                    parseContext.buildLoopBuffer = true;
                                    parseContext.ignoreLine = true;
                                    parseContext.addVariable(Tag.FOR, tag.group(1), getVariable(new selector(tag.group(2))));
                                }
                                break;
                            case ENDFOR:
                                if( parseContext.loopsSeen > 0 ){
                                    parseContext.loopsSeen--;
                                }else{
                                    // release loop variable and unset loop flag, unset loop level
                                    // perform loop on buffer bsed on collection value
                                    //      pass buffer into parseLine(writer, buffer)
                                    parseContext.ignoreLine = true;
                                    parseContext.buildLoopBuffer = false;
                                
                                    variableContext vc = parseContext.getVariableContext(Tag.FOR);
                                    int collectionSize = ((List) vc.object).size();
                                    for(int i = 0; i < collectionSize; i++){
                                        vc.index = i;

                                        parseContext newContext = new parseContext();
                                        parseContextStack.add(newContext);
                                        parse(parseContext.loopBuffer.toString());
                                        parseContextStack.remove(parseContextStack.size()-1);
                                    }
                                    parseContext.removeVariable(Tag.FOR);
                                }

                                break;
                            case IF:
                                if( parseContext.buildLoopBuffer == false){
                                    Boolean match = parseConditional(tag.group(1));
                                    parseContext.allowOutput = match;
                                    parseContext.ignoreLine = true;
                                }
                                break;
                            case ELSE:
                                if( parseContext.buildLoopBuffer == false){
                                    parseContext.allowOutput = !parseContext.allowOutput;
                                    parseContext.ignoreLine = true;
                                }
                                break;
                            case ENDIF:
                                if( parseContext.buildLoopBuffer == false){
                                    parseContext.allowOutput = true;
                                    parseContext.ignoreLine = true;
                                }
                                break;

                            case WITH:
                                if( parseContext.buildLoopBuffer == false){
                                    parseContext.addVariable(Tag.WITH, tag.group(1), getVariable(new selector(tag.group(2))));
                                    parseContext.ignoreLine = true;
                                }
                                break;

                            case ENDWITH:
                                if( parseContext.buildLoopBuffer == false){
                                    parseContext.removeVariable(Tag.WITH);
                                    parseContext.ignoreLine = true;
                                }
                                break;
                        }
                    }
                }catch(Exception e){
                    logger.warn("Cannot process tag: Exception: ",e);
                }
            }
            if(parseContext.allowOutput && 
                parseContext.ignoreLine == false){
                if(parseContext.buildLoopBuffer){
                    parseContext.loopBuffer.append(line + "\n");
                }else{
                    messageHtml.append(line+"\n");
                }
            }
        }
    }

    /*
     * Proces conditional for IF statements
     */
    // !!! ?? try to merge code with filter conditional
    private Boolean parseConditional(String condition)
    {
        Boolean match = false;

        String left;
        String operation;
        String right;


        Matcher tag = Conditional.matcher(condition);
        while( tag.find()){
            left = tag.group(1);
            operation = tag.group(2);
            right = tag.group(3);

            if(right.equals("\"\"")){
                right = "";
            }

            List<String> fields = new ArrayList<String>(Arrays.asList(left.split("\\.")));
            String object = fields.get(0);
            fields.remove(0);
            selector collectionVariableSelector = new selector(left);
            Object leftVariable = getVariable(collectionVariableSelector);

            if(leftVariable != null){
                if( operation.equals("==") ){
                    // !!! is this comparision hokey/bad form?
                    if(leftVariable.getClass().getName().equals("java.lang.Boolean")){
                        if((Boolean) leftVariable.equals(Boolean.valueOf(right))){
                            match = true;
                        }
                    }else{
                        if(leftVariable.toString().equals(right)){
                            match = true;
                        }

                        // !!! Also numeric checks.
                    }
                }else if(operation.equals("!=")){
                    if(!leftVariable.toString().equals(right)){
                        match = true;
                    }
                }
            }
        }

        return match;
    }

    /*
     * Add variables as buffered writes.  
     * Most variables are single string so this may seem like overkill but others like files 
     * are too big to keep in memory.
     */
    private void insertVariable(String line, selector variableSelector)
    {
        if(variableSelector.fields.get(0).equals("attachment")){
            insertVariableAttachment(variableSelector);
        }else{
            Object variable = getVariable(variableSelector);
            try{
                messageHtml.append(variable.toString());
            }catch(Exception e){
                logger.warn("Unable to insert variable:" + variableSelector );
            }
        }
    }

    /*
     * Add a file to the current location as-is.
     */
    private void insertVariableAttachment(selector variableSelector)
    {
        Boolean base64 = false;
        String id = null;

        for(String filter: variableSelector.filters){
            if(filter.startsWith("id=")){
                int separator = filter.indexOf("=");
                if(separator != -1){
                    id = filter.substring(separator + 1);
                }
            }
        }
        // !!! error if file not specified
        // !!! check if file exists

        Boolean duplicate = false;            
        for(int i = 0; i < messageParts.size(); i++ ){
            if(messageParts.get(i).get(MailSender.MessagePartsField.FILENAME).equals(variableSelector.arguments.get(0))){
                duplicate = true;
            }
        }
        if( duplicate == false){
            Map<MailSender.MessagePartsField,String> attachment = new HashMap<MailSender.MessagePartsField,String>();
            attachment.put(MailSender.MessagePartsField.FILENAME, variableSelector.arguments.get(0));
            if(id != null){
                attachment.put(MailSender.MessagePartsField.CID, id);
                messageHtml.append("cid:" + id);
            }
            messageParts.add(attachment);
        }
    }

    /*
     * Get a variable from its selector.  
     * This will also recurse to pull arguments into itself.
     */
    private Object getVariable(selector variableSelector)
    {
        Method method = null;
        Object object = null;
        Class<?>[] argumentTypes = null;
        Object[] argumentValues = null;

        /*
         * Look at the first selector field to determine of an object should be pulled from the VM
         * or context stack.
         */ 
        object = (Object) UvmContextFactory.context();
        int fieldIndex = 0;
        try{
            method = object.getClass().getMethod(variableSelector.fields.get(0));
        }catch(java.lang.NoSuchMethodException e){
            object = null;
            for( int i = parseContextStack.size() - 1; i >= 0; i--){
                object = parseContextStack.get(i).getVariable(variableSelector.fields.get(0));
                if(object != null){
                    break;
                }
            }
        }
        // !!! another catch here.  Return null

        /*
         * Walk the field list on the current object.
         */
        for(; fieldIndex < variableSelector.fields.size(); fieldIndex++){

            /*
             * If about to try final field, process arguments.
             */
            if((fieldIndex == variableSelector.fields.size() - 1) &&
                variableSelector.arguments.size() > 0){

                /*
                 * Get the method's argument type list
                 */                
                for(Method m: object.getClass().getMethods()){
                    if(variableSelector.fields.get(fieldIndex).equals(m.getName()) &&
                        (m.getParameterTypes().length == variableSelector.arguments.size())){
                        argumentTypes = m.getParameterTypes();
                        break;
                    }
                }

                /* Not found in VM so create new one for context stack processing*/
                if(argumentTypes == null){
                    argumentTypes = new Class<?>[variableSelector.arguments.size()];
                }

                argumentValues = new Object[variableSelector.arguments.size()];

                /*
                 * Build companion argument value list
                 */
                int argumentIndex = 0;
                for(String argument: variableSelector.arguments){
                    if(argumentTypes[argumentIndex] == null){
                        /* For non VM objects, set argument type to string. */
                        argumentTypes[argumentIndex] = String.class;
                    }

                    /*
                     * Recurse ourself to see if this argument refers to another context variable.
                     */
                    Object argumentValue = getVariable(new selector(variableSelector.arguments.get(argumentIndex)));
                    if(argumentValue != null){
                        argumentValues[argumentIndex] = argumentValue;
                    }else{
                        /*
                         * Otherwise process variable as-is and try to coerse to match the type.
                         */
                        argumentValue = variableSelector.arguments.get(argumentIndex);
                        if(argumentValue.equals("null")){
                            argumentValues[argumentIndex] = null;
                        }else if(argumentValue.getClass() != argumentTypes[argumentIndex]){
                            if(argumentTypes[argumentIndex].getName().equals("int")){
                                argumentValues[argumentIndex] = Integer.valueOf((String)argumentValue);
                            }
                        }else{
                            argumentValues[argumentIndex] = argumentValue;
                        }
                    }
                    argumentIndex++;                    
                }
            }

            /*
             * Call into VM object path, otherwise back into context stack.
             */
            try{
                if(variableSelector.arguments.size() == 0){
                    method = object.getClass().getMethod(variableSelector.fields.get(fieldIndex));
                    object = method.invoke(object);
                }else{
                    method = object.getClass().getMethod(variableSelector.fields.get(fieldIndex),argumentTypes);
                    object = method.invoke(object, argumentValues);
                }
            }catch(java.lang.NoSuchMethodException e){
                for( int c = parseContextStack.size() - 1; c >= 0; c--){
                    object = parseContextStack.get(c).getVariable(variableSelector.fields.get(0));
                    if(object != null){
                        break;
                    }
                }
            }catch(java.lang.NullPointerException e){
                /*
                 * this is ok because you asked for a null....
                 */
            }catch(Exception e){
                // !!! also provide selector
                logger.warn("Unable to get variable", e );
            }
        }

        /*
         * If selector has defined variables, process them
         */
        if(object != null &&
            variableSelector.filters.size() > 0){
            if(object.getClass().getName().contains(".LinkedList") || 
                object.getClass().getName().contains(".ArrayList")){

                for(int i = ((List) object).size() -1; i >= 0; i--){
                    if(!filterMatch(((List)object).get(i), variableSelector.filters)){
                        ((List) object).remove(i);
                    }
                }

                if(((List) object).size() > 0 &&
                    variableSelector.filters.get(0).equals("first")){
                    object = ((List) object).get(0); 
                }

            }else if(object.getClass().getName().contains(".String")){
                object = filterProcess(object,variableSelector.filters);
            }

            // !!! how to tell unknown filters?
        }

        return object;
    }

    /*
     * Similar to condtional except use object methods for comparison.
     * (e.g., "getType=TEXT" to only pull text reports)
     */
    Boolean filterMatch(Object object, List<String> filters){
        if(filters.size() == 0){
            return true;
        }

        Boolean match = false;
        Method method = null;
        Object tObject;

        String left;
        String operation;
        String right;

        Boolean filterMatchFound = false;
        Matcher tag;
        for(String filter: filters){
            tag = Conditional.matcher(filter);

            while( tag.find()){
                filterMatchFound = true;
                left = tag.group(1);
                operation = tag.group(2);
                right = tag.group(3);

                try{
                    method = object.getClass().getMethod(left);
                    tObject = method.invoke(object);

                    if(operation.equals("==")){
                        if(tObject.toString().equals(right)){
                            match = true;
                        }
                    }
                    // !!! Other operations...
                }catch(Exception e){
                    logger.warn("Unable to process filter match:" + e);
                }
            }

        }
        if(filterMatchFound == false){
            return true;
        }
        return match;
    }

    /*
     * Modify the object.
     */
    Object filterProcess(Object object, List<String> filters){
        Matcher filterMatcher;
        for(String filter: filters){
            for (Map.Entry<Filter, Pattern> syntax : FilterPatterns.entrySet()) {
                try{
                    filterMatcher = syntax.getValue().matcher(filter);
                    while( filterMatcher.find()){
                        switch(syntax.getKey()){
                            case FORMAT:
                                object = filterProcessFormat(object, (JSONObject) getVariable(new selector(filterMatcher.group(1))));
                                break;
                        }
                    }
                }catch(Exception e){
                    logger.warn("Unable to process filter:" + e);
                }
            }
        }
        return object;
    }

    /*
     * Process a template through an argument list
     */
    Object filterProcessFormat(Object template, JSONObject arguments){
        /*
         * It's not a guaranteee that the arguments list will match the number of
         * template arguments (e.g.,.SQL will leave fields blank instead of empty unless
         * extra processing is done).  Determine how many we'll need and after building
         * replacement map, add the extras we need with known defaults for reports ("0")
         */
        int maximumTemplateArguments = 0;
        int argumentIndex = 0;
        while(true){
            if(((String)template).indexOf("{" + Integer.toString(maximumTemplateArguments) + "}") == -1){
                break;
            }
            maximumTemplateArguments++;
        }

        Map<String,String> replacements = new HashMap<String,String>();
        if(arguments.names() != null){
            for( argumentIndex = 0; argumentIndex < arguments.names().length(); argumentIndex++){
                try{
                    replacements.put("{" + Integer.toString(argumentIndex) + "}", arguments.get(arguments.names().getString(argumentIndex)).toString() );
                }catch(Exception e){
                    logger.warn("Unable to process argument entry " + Integer.toString(argumentIndex) + " :" + e);
                }
            }
        }

        while(argumentIndex < maximumTemplateArguments){
            replacements.put("{" + Integer.toString(argumentIndex) + "}", "0" );
            argumentIndex++;
        }

        String formatted = (String) template;
        for (Map.Entry<String,String> sr : replacements.entrySet()) {
            formatted = formatted.replace(sr.getKey(), sr.getValue());
        }

        return (Object) formatted;
    }
}