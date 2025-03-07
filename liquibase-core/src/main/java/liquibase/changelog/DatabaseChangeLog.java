package liquibase.changelog;

import liquibase.*;
import liquibase.changelog.filter.ContextChangeSetFilter;
import liquibase.changelog.filter.DbmsChangeSetFilter;
import liquibase.changelog.filter.LabelChangeSetFilter;
import liquibase.changelog.visitor.ValidatingVisitor;
import liquibase.database.Database;
import liquibase.database.DatabaseList;
import liquibase.database.ObjectQuotingStrategy;
import liquibase.exception.*;
import liquibase.logging.Logger;
import liquibase.parser.ChangeLogParser;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.parser.core.ParsedNode;
import liquibase.parser.core.ParsedNodeException;
import liquibase.precondition.Conditional;
import liquibase.precondition.Precondition;
import liquibase.precondition.core.PreconditionContainer;
import liquibase.resource.ResourceAccessor;
import liquibase.servicelocator.LiquibaseService;
import liquibase.ui.UIService;
import liquibase.util.StringUtil;
import liquibase.util.FilenameUtil;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Encapsulates the information stored in the change log XML file.
 */
public class DatabaseChangeLog implements Comparable<DatabaseChangeLog>, Conditional {
    private static final ThreadLocal<DatabaseChangeLog> ROOT_CHANGE_LOG = new ThreadLocal<>();
    private static final ThreadLocal<DatabaseChangeLog> PARENT_CHANGE_LOG = new ThreadLocal<>();
    private static final Logger LOG = Scope.getCurrentScope().getLog(DatabaseChangeLog.class);

    public static final Pattern CLASSPATH_PATTERN = Pattern.compile("classpath:");
    public static final Pattern SLASH_PATTERN = Pattern.compile("^/");
    public static final Pattern NON_CLASSPATH_PATTERN = Pattern.compile("^classpath:");
    public static final Pattern DOUBLE_BACK_SLASH_PATTERN = Pattern.compile("\\\\");
    public static final Pattern DOUBLE_SLASH_PATTERN = Pattern.compile("//+");
    public static final Pattern SLASH_DOT_SLASH_PATTERN = Pattern.compile("/\\./");
    public static final Pattern NO_LETTER_PATTERN = Pattern.compile("^[a-zA-Z]:");
    public static final Pattern DOT_SLASH_PATTERN = Pattern.compile("^\\.?/");

    private PreconditionContainer preconditionContainer = new GlobalPreconditionContainer();
    private String physicalFilePath;
    private String logicalFilePath;
    private String changeLogId;
    private ObjectQuotingStrategy objectQuotingStrategy;

    private List<ChangeSet> changeSets = new ArrayList<>();
    private ChangeLogParameters changeLogParameters;

    private RuntimeEnvironment runtimeEnvironment;

    private DatabaseChangeLog rootChangeLog = ROOT_CHANGE_LOG.get();

    private DatabaseChangeLog parentChangeLog = PARENT_CHANGE_LOG.get();

    private ContextExpression contexts;

    private ContextExpression includeContexts;

    private Labels includeLabels;
    private boolean includeIgnore;

    public DatabaseChangeLog() {
    }

    public DatabaseChangeLog(String physicalFilePath) {
        this.physicalFilePath = physicalFilePath;
    }

    public void setRootChangeLog(DatabaseChangeLog rootChangeLog) {
        this.rootChangeLog = rootChangeLog;
    }

    public DatabaseChangeLog getRootChangeLog() {
        return (rootChangeLog != null) ? rootChangeLog : this;
    }

    public void setParentChangeLog(DatabaseChangeLog parentChangeLog) {
        this.parentChangeLog = parentChangeLog;
    }

    public DatabaseChangeLog getParentChangeLog() {
        return parentChangeLog;
    }

    public RuntimeEnvironment getRuntimeEnvironment() {
        return runtimeEnvironment;
    }

    public void setRuntimeEnvironment(RuntimeEnvironment runtimeEnvironment) {
        this.runtimeEnvironment = runtimeEnvironment;
    }

    @Override
    public PreconditionContainer getPreconditions() {
        return preconditionContainer;
    }

    @Override
    public void setPreconditions(PreconditionContainer precond) {
        this.preconditionContainer.addNestedPrecondition(precond);
    }


    public ChangeLogParameters getChangeLogParameters() {
        return changeLogParameters;
    }

    public void setChangeLogParameters(ChangeLogParameters changeLogParameters) {
        this.changeLogParameters = changeLogParameters;
    }

    public String getPhysicalFilePath() {
        return physicalFilePath;
    }

    public void setPhysicalFilePath(String physicalFilePath) {
        this.physicalFilePath = physicalFilePath;
    }

    public String getChangeLogId() {
        return changeLogId;
    }

    public void setChangeLogId(String changeLogId) {
        this.changeLogId = changeLogId;
    }

    public String getLogicalFilePath() {
        String returnPath = logicalFilePath;
        if (logicalFilePath == null) {
            returnPath = physicalFilePath;
        }
        if (returnPath == null) {
            return null;
        }
        String path = DOUBLE_BACK_SLASH_PATTERN.matcher(returnPath).replaceAll("/");
        return SLASH_PATTERN.matcher(path).replaceFirst("");
    }

    public void setLogicalFilePath(String logicalFilePath) {
        this.logicalFilePath = logicalFilePath;
    }

    public String getFilePath() {
        if (logicalFilePath == null) {
            return physicalFilePath;
        } else {
            return getLogicalFilePath();
        }
    }

    public ObjectQuotingStrategy getObjectQuotingStrategy() {
        return objectQuotingStrategy;
    }

    public void setObjectQuotingStrategy(ObjectQuotingStrategy objectQuotingStrategy) {
        this.objectQuotingStrategy = objectQuotingStrategy;
    }

    public ContextExpression getContexts() {
        return contexts;
    }

    public void setContexts(ContextExpression contexts) {
        this.contexts = contexts;
    }

    public ContextExpression getIncludeContexts() {
        return includeContexts;
    }

    /**
     * @deprecated Correct version is {@link #setIncludeLabels(Labels)}. Kept for backwards compatibility.
     */
    public void setIncludeLabels(LabelExpression labels) {
        this.includeLabels = new Labels(labels.toString());
    }

    public void setIncludeLabels(Labels labels) {
        this.includeLabels = labels;
    }

    public Labels getIncludeLabels() {
        return includeLabels;
    }

    public void setIncludeIgnore(boolean ignore) {
        this.includeIgnore = ignore;
    }

    public boolean isIncludeIgnore() {
        return this.includeIgnore;
    }

    public void setIncludeContexts(ContextExpression includeContexts) {
        this.includeContexts = includeContexts;
    }

    @Override
    public String toString() {
        return getFilePath();
    }

    @Override
    public int compareTo(DatabaseChangeLog o) {
        return getFilePath().compareTo(o.getFilePath());
    }

    public ChangeSet getChangeSet(String path, String author, String id) {
        for (ChangeSet changeSet : changeSets) {
            final String normalizedPath = normalizePath(changeSet.getFilePath());
            if (normalizedPath != null &&
                    normalizedPath.equalsIgnoreCase(normalizePath(path)) &&
                    changeSet.getAuthor().equalsIgnoreCase(author) &&
                    changeSet.getId().equalsIgnoreCase(id) &&
                    isDbmsMatch(changeSet.getDbmsSet())) {
                return changeSet;
            }
        }

        return null;
    }

    public List<ChangeSet> getChangeSets() {
        return changeSets;
    }

    public void addChangeSet(ChangeSet changeSet) {
        if (changeSet.getRunOrder() == null) {
            ListIterator<ChangeSet> it = this.changeSets.listIterator(this.changeSets.size());
            boolean added = false;
            while (it.hasPrevious() && !added) {
                if (!"last".equals(it.previous().getRunOrder())) {
                    it.next();
                    it.add(changeSet);
                    added = true;
                }
            }
            if (!added) {
                it.add(changeSet);
            }

        } else if ("first".equals(changeSet.getRunOrder())) {
            ListIterator<ChangeSet> it = this.changeSets.listIterator();
            boolean added = false;
            while (it.hasNext() && !added) {
                if (!"first".equals(it.next().getRunOrder())) {
                    it.previous();
                    it.add(changeSet);
                    added = true;
                }
            }
            if (!added) {
                this.changeSets.add(changeSet);
            }
        } else if ("last".equals(changeSet.getRunOrder())) {
            this.changeSets.add(changeSet);
        } else {
            throw new UnexpectedLiquibaseException("Unknown runOrder: " + changeSet.getRunOrder());
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if ((o == null) || (getClass() != o.getClass())) {
            return false;
        }

        DatabaseChangeLog that = (DatabaseChangeLog) o;

        return getFilePath().equals(that.getFilePath());

    }

    @Override
    public int hashCode() {
        return getFilePath().hashCode();
    }

    public void validate(Database database, String... contexts) throws LiquibaseException {
        this.validate(database, new Contexts(contexts), new LabelExpression());
    }

    public void validate(Database database, Contexts contexts, LabelExpression labelExpression)
            throws LiquibaseException {

        database.setObjectQuotingStrategy(objectQuotingStrategy);

        ChangeLogIterator logIterator = new ChangeLogIterator(
                this,
                new DbmsChangeSetFilter(database),
                new ContextChangeSetFilter(contexts),
                new LabelChangeSetFilter(labelExpression)
        );

        ValidatingVisitor validatingVisitor = new ValidatingVisitor(database.getRanChangeSetList());
        validatingVisitor.validate(database, this);
        logIterator.run(validatingVisitor, new RuntimeEnvironment(database, contexts, labelExpression));

        final Logger log = Scope.getCurrentScope().getLog(getClass());
        for (String message : validatingVisitor.getWarnings().getMessages()) {
            log.warning(message);
        }

        if (!validatingVisitor.validationPassed()) {
            throw new ValidationFailedException(validatingVisitor);
        }
    }

    public ChangeSet getChangeSet(RanChangeSet ranChangeSet) {
        final ChangeSet changeSet = getChangeSet(ranChangeSet.getChangeLog(), ranChangeSet.getAuthor(), ranChangeSet.getId());
        if (changeSet != null) {
            changeSet.setStoredFilePath(ranChangeSet.getStoredChangeLog());
        }
        return changeSet;
    }

    public void load(ParsedNode parsedNode, ResourceAccessor resourceAccessor) throws ParsedNodeException, SetupException {
        setChangeLogId(parsedNode.getChildValue(null, "changeLogId", String.class));
        setLogicalFilePath(parsedNode.getChildValue(null, "logicalFilePath", String.class));
        setContexts(new ContextExpression(parsedNode.getChildValue(null, "context", String.class)));
        String objectQuotingStrategy = parsedNode.getChildValue(null, "objectQuotingStrategy", String.class);
        if (objectQuotingStrategy != null) {
            setObjectQuotingStrategy(ObjectQuotingStrategy.valueOf(objectQuotingStrategy));
        }
        for (ParsedNode childNode : parsedNode.getChildren()) {
            handleChildNode(childNode, resourceAccessor);
        }
    }

    protected void expandExpressions(ParsedNode parsedNode) throws UnknownChangeLogParameterException {
        if (changeLogParameters == null) {
            return;
        }
        try {
            Object value = parsedNode.getValue();
            if ((value != null) && (value instanceof String)) {
                parsedNode.setValue(changeLogParameters.expandExpressions(parsedNode.getValue(String.class), this));
            }

            List<ParsedNode> children = parsedNode.getChildren();
            if (children != null) {
                for (ParsedNode child : children) {
                    expandExpressions(child);
                }
            }
        } catch (ParsedNodeException e) {
            throw new UnexpectedLiquibaseException(e);
        }
    }

    protected void handleChildNode(ParsedNode node, ResourceAccessor resourceAccessor) throws ParsedNodeException, SetupException {
        expandExpressions(node);
        String nodeName = node.getName();
        switch (nodeName) {
            case "changeSet":
                if (isDbmsMatch(node.getChildValue(null, "dbms", String.class))) {
                    this.addChangeSet(createChangeSet(node, resourceAccessor));
                }
                break;
            case "include": {
                String path = node.getChildValue(null, "file", String.class);
                if (path == null) {
                    throw new UnexpectedLiquibaseException("No 'file' attribute on 'include'");
                }
                path = path.replace('\\', '/');
                ContextExpression includeContexts = new ContextExpression(node.getChildValue(null, "context", String.class));
                Labels labels = new Labels(node.getChildValue(null, "labels", String.class));
                Boolean ignore = node.getChildValue(null, "ignore", Boolean.class);
                try {
                    include(path,
                            node.getChildValue(null, "relativeToChangelogFile", false),
                            resourceAccessor,
                            includeContexts,
                            labels,
                            ignore,
                            OnUnknownFileFormat.FAIL);
                } catch (LiquibaseException e) {
                    throw new SetupException(e);
                }
                break;
            }
            case "includeAll": {
                String path = node.getChildValue(null, "path", String.class);
                String resourceFilterDef = node.getChildValue(null, "filter", String.class);
                if (resourceFilterDef == null) {
                    resourceFilterDef = node.getChildValue(null, "resourceFilter", String.class);
                }
                IncludeAllFilter resourceFilter = null;
                if (resourceFilterDef != null) {
                    try {
                        resourceFilter = (IncludeAllFilter) Class.forName(resourceFilterDef).getConstructor().newInstance();
                    } catch (ReflectiveOperationException e) {
                        throw new SetupException(e);
                    }
                }

                String resourceComparatorDef = node.getChildValue(null, "resourceComparator", String.class);
                Comparator<String> resourceComparator = null;
                if (resourceComparatorDef != null) {
                    try {
                        resourceComparator = (Comparator<String>) Class.forName(resourceComparatorDef).getConstructor().newInstance();
                    } catch (ReflectiveOperationException e) {
                        //take default comparator
                        Scope.getCurrentScope().getLog(getClass()).info("no resourceComparator defined - taking default " +
                                "implementation");
                        resourceComparator = getStandardChangeLogComparator();
                    }
                }

                ContextExpression includeContexts = new ContextExpression(node.getChildValue(null, "context", String.class));
                Labels labels = new Labels(node.getChildValue(null, "labels", String.class));
                Boolean ignore = node.getChildValue(null, "ignore", Boolean.class);
                if (ignore == null) {
                    ignore = false;
                }
                includeAll(path, node.getChildValue(null, "relativeToChangelogFile", false), resourceFilter,
                        node.getChildValue(null, "errorIfMissingOrEmpty", true),
                        resourceComparator,
                        resourceAccessor,
                        includeContexts,
                        labels,
                        ignore);
                break;
            }
            case "preConditions": {
                PreconditionContainer parsedContainer = new PreconditionContainer();
                parsedContainer.load(node, resourceAccessor);
                this.preconditionContainer.addNestedPrecondition(parsedContainer);

                break;
            }
            case "property": {
                try {
                    String context = node.getChildValue(null, "context", String.class);
                    String dbms = node.getChildValue(null, "dbms", String.class);
                    String labels = node.getChildValue(null, "labels", String.class);
                    Boolean global = node.getChildValue(null, "global", Boolean.class);
                    if (global == null) {
                        // okay behave like liquibase < 3.4 and set global == true
                        global = true;
                    }

                    String file = node.getChildValue(null, "file", String.class);

                    if (file == null) {
                        // direct referenced property, no file
                        String name = node.getChildValue(null, "name", String.class);
                        String value = node.getChildValue(null, "value", String.class);

                        this.changeLogParameters.set(name, value, context, labels, dbms, global, this);
                    } else {
                        // read properties from the file
                        Properties props = new Properties();
                        try (InputStream propertiesStream = resourceAccessor.openStream(null, file)) {
                            if (propertiesStream != null) {
                                props.load(propertiesStream);

                                for (Map.Entry<Object, Object> entry : props.entrySet()) {
                                    this.changeLogParameters.set(
                                            entry.getKey().toString(),
                                            entry.getValue().toString(),
                                            context,
                                            labels,
                                            dbms,
                                            global,
                                            this
                                    );
                                }
                            } else {
                                Scope.getCurrentScope().getLog(getClass()).info("Could not open properties file " + file);
                            }
                        }
                    }
                } catch (IOException e) {
                    throw new ParsedNodeException(e);
                }

                break;
            }
            default:
                // we want to exclude child nodes that are not changesets or the other things
                // and avoid failing when encountering "child" nodes of the databaseChangeLog which are just
                // XML node attributes (like schemaLocation). If you don't understand, remove the if and run the tests
                // and look at the error output or review the "node" object here with a debugger.
                if (node.getChildren() != null && !node.getChildren().isEmpty()) {
                    throw new ParsedNodeException("Unexpected node found under databaseChangeLog: " + nodeName);
                }
        }
    }

    public boolean isDbmsMatch(String dbmsList) {
        return isDbmsMatch(DatabaseList.toDbmsSet(dbmsList));
    }

    public boolean isDbmsMatch(Set<String> dbmsSet) {
        return dbmsSet == null
                || changeLogParameters == null
                || changeLogParameters.getValue("database.typeName", this) == null
                || DatabaseList.definitionMatches(dbmsSet, changeLogParameters.getValue("database.typeName", this).toString(), true);
    }

    /**
     * @deprecated Incorrect LabelExpression parameter. Kept for backwards compatibility
     */
    @Deprecated
    public void includeAll(String pathName,
                           boolean isRelativeToChangelogFile,
                           IncludeAllFilter resourceFilter,
                           boolean errorIfMissingOrEmpty,
                           Comparator<String> resourceComparator,
                           ResourceAccessor resourceAccessor,
                           ContextExpression includeContexts,
                           LabelExpression labelExpression,
                           boolean ignore)
            throws SetupException {
        Labels labels = null;
        if (labelExpression != null && !labelExpression.isEmpty()) {
            labels = new Labels(labelExpression.toString());
        }
        includeAll(pathName,
                isRelativeToChangelogFile,
                resourceFilter,
                errorIfMissingOrEmpty,
                resourceComparator,
                resourceAccessor,
                includeContexts,
                labels,
                ignore);
    }

    public void includeAll(String pathName,
                           boolean isRelativeToChangelogFile,
                           IncludeAllFilter resourceFilter,
                           boolean errorIfMissingOrEmpty,
                           Comparator<String> resourceComparator,
                           ResourceAccessor resourceAccessor,
                           ContextExpression includeContexts,
                           Labels labels,
                           boolean ignore)
            throws SetupException {
        try {
            if (pathName == null) {
                throw new SetupException("No path attribute for includeAll");
            }
            pathName = pathName.replace('\\', '/');

            if (StringUtil.isNotEmpty(pathName) && !(pathName.endsWith("/"))) {
                pathName = pathName + '/';
            }
            LOG.fine("includeAll for " + pathName);
            LOG.fine("Using file opener for includeAll: " + resourceAccessor.toString());

            String relativeTo = null;
            if (isRelativeToChangelogFile) {
                relativeTo = this.getPhysicalFilePath();
            }

            Set<String> unsortedResources = null;
            try {
                unsortedResources = resourceAccessor.list(relativeTo, pathName, true, true, false);
            } catch (IOException e) {
                if (errorIfMissingOrEmpty) {
                    throw e;
                }
            }
            SortedSet<String> resources = new TreeSet<>(resourceComparator);
            if (unsortedResources != null) {
                for (String resourcePath : unsortedResources) {
                    if ((resourceFilter == null) || resourceFilter.include(resourcePath)) {
                        resources.add(resourcePath);
                    }
                }
            }

            if (resources.isEmpty() && errorIfMissingOrEmpty) {
                throw new SetupException(
                        "Could not find directory or directory was empty for includeAll '" + pathName + "'");
            }

            for (String path : resources) {
                Scope.getCurrentScope().getLog(getClass()).info("Reading resource: " + path);
                include(path, false, resourceAccessor, includeContexts, labels, ignore, OnUnknownFileFormat.WARN);
            }
        } catch (Exception e) {
            throw new SetupException(e);
        }
    }

    /**
     * @deprecated Incorrect LabelExpression parameter. Kept for backwards compatibility
     */
    @Deprecated
    public boolean include(String fileName,
                           boolean isRelativePath,
                           ResourceAccessor resourceAccessor,
                           ContextExpression includeContexts,
                           LabelExpression labelExpression,
                           Boolean ignore,
                           boolean logEveryUnknownFileFormat)
            throws LiquibaseException {
        Labels labels = null;
        if (labelExpression != null && !labelExpression.isEmpty()) {
            labels = new Labels(labelExpression.getLabels());
        }

        return include(fileName,
                isRelativePath,
                resourceAccessor,
                includeContexts,
                labels,
                ignore,
                logEveryUnknownFileFormat);
    }

    /**
     * @deprecated
     */
    public boolean include(String fileName,
                           boolean isRelativePath,
                           ResourceAccessor resourceAccessor,
                           ContextExpression includeContexts,
                           Labels labels,
                           Boolean ignore,
                           boolean logEveryUnknownFileFormat)
            throws LiquibaseException {
        return include(fileName, isRelativePath, resourceAccessor, includeContexts, labels, ignore, logEveryUnknownFileFormat ? OnUnknownFileFormat.WARN : OnUnknownFileFormat.SKIP);
    }

    public boolean include(String fileName,
                           boolean isRelativePath,
                           ResourceAccessor resourceAccessor,
                           ContextExpression includeContexts,
                           Labels labels,
                           Boolean ignore,
                           OnUnknownFileFormat onUnknownFileFormat)
            throws LiquibaseException {

        if (".svn".equalsIgnoreCase(fileName) || "cvs".equalsIgnoreCase(fileName)) {
            return false;
        }

        String relativeBaseFileName = this.getPhysicalFilePath();
        if (isRelativePath) {
            relativeBaseFileName = CLASSPATH_PATTERN.matcher(relativeBaseFileName).replaceFirst("");
            fileName = FilenameUtil.concat(FilenameUtil.getDirectory(relativeBaseFileName), fileName);
        }
        fileName = CLASSPATH_PATTERN.matcher(fileName).replaceFirst("");
        DatabaseChangeLog changeLog;
        try {
            DatabaseChangeLog rootChangeLog = ROOT_CHANGE_LOG.get();
            if (rootChangeLog == null) {
                ROOT_CHANGE_LOG.set(this);
            }
            DatabaseChangeLog parentChangeLog = PARENT_CHANGE_LOG.get();
            PARENT_CHANGE_LOG.set(this);
            try {
                ChangeLogParser parser = ChangeLogParserFactory.getInstance().getParser(fileName, resourceAccessor);
                changeLog = parser.parse(fileName, changeLogParameters, resourceAccessor);
                changeLog.setIncludeContexts(includeContexts);
                changeLog.setIncludeLabels(labels);
                changeLog.setIncludeIgnore(ignore != null ? ignore.booleanValue() : false);
            } finally {
                if (rootChangeLog == null) {
                    ROOT_CHANGE_LOG.remove();
                }
                if (parentChangeLog == null) {
                    PARENT_CHANGE_LOG.remove();
                } else {
                    PARENT_CHANGE_LOG.set(parentChangeLog);
                }
            }
        } catch (UnknownChangelogFormatException e) {
            if (onUnknownFileFormat == OnUnknownFileFormat.FAIL) {
                throw e;
            }
            // This matches only an extension, but filename can be a full path, too. Is it right?
            boolean matchesFileExtension = StringUtil.trimToEmpty(fileName).matches("\\.\\w+$");
            if (matchesFileExtension || onUnknownFileFormat == OnUnknownFileFormat.WARN) {
                Scope.getCurrentScope().getLog(getClass()).warning(
                        "included file " + relativeBaseFileName + "/" + fileName + " is not a recognized file type"
                );
            }
            return false;
        }
        PreconditionContainer preconditions = changeLog.getPreconditions();
        if (preconditions != null) {
            if (null == this.getPreconditions()) {
                this.setPreconditions(new PreconditionContainer());
            }
            this.getPreconditions().addNestedPrecondition(preconditions);
        }
        for (ChangeSet changeSet : changeLog.getChangeSets()) {
            addChangeSet(changeSet);
        }

        return true;
    }

    protected ChangeSet createChangeSet(ParsedNode node, ResourceAccessor resourceAccessor) throws ParsedNodeException {
        ChangeSet changeSet = new ChangeSet(this);
        changeSet.setChangeLogParameters(this.getChangeLogParameters());
        changeSet.load(node, resourceAccessor);
        return changeSet;
    }

    protected Comparator<String> getStandardChangeLogComparator() {
        return new Comparator<String>() {
            @Override
            public int compare(String o1, String o2) {
                //by ignoring WEB-INF/classes in path all changelog Files independent
                //whehther they are in a WAR or in a JAR are order following the same rule
                return o1.replace("WEB-INF/classes/", "").compareTo(o2.replace("WEB-INF/classes/", ""));
            }
        };
    }

    public static String normalizePath(String filePath) {
        if (filePath == null) {
            return null;
        }
        String noClassPathReplaced = NON_CLASSPATH_PATTERN.matcher(filePath).replaceFirst("");
        String doubleBackSlashReplaced = DOUBLE_BACK_SLASH_PATTERN.matcher(noClassPathReplaced).replaceAll("/");
        String doubleSlashReplaced = DOUBLE_SLASH_PATTERN.matcher(doubleBackSlashReplaced).replaceAll("/");
        String slashDotSlashReplaced =SLASH_DOT_SLASH_PATTERN.matcher(doubleSlashReplaced).replaceAll("/");
        String noLetterReplaced = NO_LETTER_PATTERN.matcher(slashDotSlashReplaced).replaceFirst("");
        return DOT_SLASH_PATTERN.matcher(noLetterReplaced).replaceFirst("");
    }

    public void clearCheckSums() {
        for (ChangeSet changeSet : getChangeSets()) {
            changeSet.clearCheckSum();
        }
    }

    /**
     * Holder for the PreconditionContainer for this changelog, plus any nested changelogs.
     */
    @LiquibaseService(skip = true)
    private static class GlobalPreconditionContainer extends PreconditionContainer {

        /**
         * This container should always be TEST because it may contain a mix of containers which may or may not get tested during update-sql
         */
        @Override
        public OnSqlOutputOption getOnSqlOutput() {
            return OnSqlOutputOption.TEST;
        }

        @Override
        public void addNestedPrecondition(Precondition precondition) {
            super.addNestedPrecondition(precondition);
        }
    }

    /**
     * Controls what to do when including a file with a format that isn't recognized by a changelog parser.
     */
    public enum OnUnknownFileFormat {

        /** Silently skip unknown files.*/
        SKIP,

        /**  Log a warning about the file not being in a recognized format, but continue on */
        WARN,

        /** Fail parsing with an error */
        FAIL
    }

}
