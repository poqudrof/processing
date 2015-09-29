/* -*- mode: java; c-basic-offset: 2; indent-tabs-mode: nil -*- */

/*
Part of the Processing project - http://processing.org
Copyright (c) 2012-15 The Processing Foundation

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License version 2
as published by the Free Software Foundation.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software Foundation, Inc.
51 Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
*/

package processing.mode.java.pdex;

import java.awt.EventQueue;
import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.Element;
import javax.swing.text.PlainDocument;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;

import processing.app.Library;
import processing.app.Messages;
import processing.app.Preferences;
import processing.app.Sketch;
import processing.app.SketchCode;
import processing.app.Util;
import processing.app.syntax.SyntaxDocument;
import processing.app.ui.Editor;
import processing.app.ui.EditorStatus;
import processing.app.ui.ErrorTable;
import processing.core.PApplet;
import processing.mode.java.JavaMode;
import processing.mode.java.JavaEditor;
import processing.mode.java.preproc.PdePreprocessor;


/**
 * The main error checking service
 */
@SuppressWarnings("unchecked")
public class ErrorCheckerService {

  protected JavaEditor editor;

  /** The amazing eclipse ast parser */
  protected ASTParser parser;

  /**
   * Used to indirectly stop the Error Checker Thread
   */
  private volatile boolean running;

  /**
   * If true, Error Checking is paused. Calls to checkCode() become useless.
   */
  protected AtomicBoolean pauseThread;

  //protected ErrorWindow errorWindow;

  /**
   * Class name of current sketch
   */
  protected String className;

  /**
   * URLs of extra imports jar files stored here.
   */
  protected URL[] classPath;

  /**
   * Stores all Problems in the sketch
   */
  //public List<Problem> problemsList;

  /**
   * How many lines are present till the initial class declaration? In static
   * mode, this would include imports, class declaration and setup
   * declaration. In nomral mode, this would include imports, class
   * declaration only. It's fate is decided inside preprocessCode()
   */
  public int mainClassOffset;

  /**
   * Fixed p5 offsets for all sketches
   */
  public int defaultImportsOffset;

  /**
   * Is the sketch running in static mode or active mode?
   */
  public PdePreprocessor.Mode mode = PdePreprocessor.Mode.ACTIVE;

  /**
   * If true, compilation checker will be reloaded with updated classpath
   * items.
   */
  protected boolean loadCompClass = true;

  /**
   * Compiler Checker class. Note that methods for compilation checking are
   * called from the compilationChecker object, not from this
   */
  protected Class<?> checkerClass;

  /**
   * Compilation Checker object.
   */
  protected CompilationChecker compilationChecker;


  /**
   * List of jar files to be present in compilation checker's classpath
   */
  protected List<URL> classpathJars;

  /**
   * Used to detect if the current tab index has changed and thus repaint the
   * textarea.
   */
  public int currentTab = 0, lastTab = 0;

  /**
   * Stores the current import statements in the program. Used to compare for
   * changed import statements and update classpath if needed.
   */
  protected ArrayList<ImportStatement> programImports;

  /**
   * List of imports when sketch was last checked. Used for checking for
   * changed imports
   */
  protected ArrayList<ImportStatement> previousImports = new ArrayList<ImportStatement>();

  /**
   * List of import statements for any .jar files in the code folder.
   */
  protected ArrayList<ImportStatement> codeFolderImports = new ArrayList<ImportStatement>();

  /**
   * Teh Preprocessor
   */
  protected XQPreprocessor xqpreproc;

  /**
   * Regexp for import statements. (Used from Processing source)
   */
  public static final String IMPORT_REGEX =
    "(?:^|;)\\s*(import\\s+)((?:static\\s+)?\\S+)(\\s*;)";

//  /**
//   * Regexp for function declarations. (Used from Processing source)
//   */
//  final Pattern FUNCTION_DECL = Pattern
//    .compile("(^|;)\\s*((public|private|protected|final|static)\\s+)*"
//      + "(void|int|float|double|String|char|byte|boolean)"
//      + "(\\s*\\[\\s*\\])?\\s+[a-zA-Z0-9]+\\s*\\(", Pattern.MULTILINE);

//  /**
//   * Matches setup or draw function declaration. We search for all those
//   * modifiers and return types in order to have proper error message
//   * when people use incompatible modifiers or non-void return type
//   */
//  private static final Pattern SETUP_OR_DRAW_FUNCTION_DECL =
//      Pattern.compile("(^|;)\\s*((public|private|protected|final|static)\\s+)*" +
//                      "(void|int|float|double|String|char|byte|boolean)" +
//                      "(\\s*\\[\\s*\\])?\\s+(setup|draw)\\s*\\(",
//                      Pattern.MULTILINE);

  protected ErrorMessageSimplifier errorMsgSimplifier;

  public ErrorCheckerService(JavaEditor debugEditor) {
    this.editor = debugEditor;
    pauseThread = new AtomicBoolean(false);

    classpathJars = new ArrayList<>();

    { // Init parser
      try {
        parser = ASTParser.newParser(AST.JLS8);
      } catch (Exception e) {
        System.err.println("Experimental Mode initialization failed. "
                               + "Are you running the right version of Processing? ");
        cancel();
      } catch (Error e) {
        System.err.println("Experimental Mode initialization failed. ");
        e.printStackTrace();
        cancel();
      }
    }


    //initializeErrorWindow();
    xqpreproc = new XQPreprocessor(this);
    PdePreprocessor pdePrepoc = new PdePreprocessor(null);
    defaultImportsOffset = pdePrepoc.getCoreImports().length +
        pdePrepoc.getDefaultImports().length + 1;
    astGenerator = new ASTGenerator(this);
    errorMsgSimplifier = new ErrorMessageSimplifier();
    tempErrorLog = new TreeMap<>();
    sketchChangedListener = new SketchChangedListener();
//    for (final SketchCode sc : editor.getSketch().getCode()) {
//      sc.getDocument().addDocumentListener(sketchChangedListener);
//    }
  }


  /**
   * Error checking doesn't happen before this interval has ellapsed since the
   * last runManualErrorCheck() call.
   */
  private final static long errorCheckInterval = 650;


  private Thread errorCheckerThread;
  private BlockingQueue<Boolean> requestQueue = new ArrayBlockingQueue<>(1);
  private ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor();
  volatile ScheduledFuture<?> scheduledUiUpdate = null;
  volatile long nextUiUpdate = 0;

  private Runnable mainLoop = new Runnable() {
    @Override
    public void run() {
      running = true;

      lastCodeCheckResult = checkCode();

      if (!lastCodeCheckResult.syntaxErrors) {
//      editor.showProblemListView(Language.text("editor.footer.console"));
        editor.showConsole();
      }
      // Make sure astGen has at least one CU to start with
      // This is when the loaded sketch already has syntax errors.
      // Completion wouldn't be complete, but it'd be still something
      // better than nothing
      synchronized (astGenerator) {
        astGenerator.buildAST(lastCodeCheckResult.sourceCode,
                              lastCodeCheckResult.compilationUnit);
      }
      handleErrorCheckingToggle();

      while (running) {
        try {
          requestQueue.take(); // blocking until there is more work
        } catch (InterruptedException e) {
          break;
        }
        requestQueue.clear();

        try {

          Messages.log("Starting error check");
          lastCodeCheckResult = checkCode();

          if (!JavaMode.errorCheckEnabled) {
            lastCodeCheckResult.problems.clear();
            Messages.log("Error Check disabled, so not updating UI.");
          }

          checkForMissingImports();

          updateSketchCodeListeners();

          if (JavaMode.errorCheckEnabled) {
            if (scheduledUiUpdate != null) {
              scheduledUiUpdate.cancel(true);
            }
            // Update UI after a delay. See #2677
            long delay = nextUiUpdate - System.currentTimeMillis();
            Runnable uiUpdater = new Runnable() {
              final CodeCheckResult result = lastCodeCheckResult;

              @Override
              public void run() {
                if (nextUiUpdate > 0 &&
                    System.currentTimeMillis() >= nextUiUpdate) {
                  EventQueue.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                      calcPdeOffsetsForProbList(result);
                      updateErrorTable(result.problems);
                      editor.updateErrorBar(result.problems);
                      editor.getTextArea().repaint();
                      editor.updateErrorToggle(result.containsErrors);
                      updateSketchCodeListeners();
                    }
                  });
                }
              }
            };
            scheduledUiUpdate = scheduler.schedule(uiUpdater, delay,
                                                   TimeUnit.MILLISECONDS);
          }
        } catch (Exception e) {
          e.printStackTrace();
        }
      }

      synchronized (astGenerator) {
        astGenerator.disposeAllWindows();
      }
      compilationChecker = null;
      checkerClass = null;
      classLoader = null;
      System.gc();
      Messages.loge("Thread stopped: " + editor.getSketch().getName());
      System.gc();

      running = false;
    }
  };


  public void start() {
    errorCheckerThread = new Thread(mainLoop);
    errorCheckerThread.start();
  }

  public void stop() {
    cancel();
    running = false;
    errorCheckerThread.interrupt();
  }


  public void cancel() {
    requestQueue.clear();
    nextUiUpdate = 0;
    if (scheduledUiUpdate != null) {
      scheduledUiUpdate.cancel(true);
    }
  }


  public void request() {
    nextUiUpdate = System.currentTimeMillis() + errorCheckInterval;
    requestQueue.offer(Boolean.TRUE);
  }


  protected void updateSketchCodeListeners() {
    for (SketchCode sc : editor.getSketch().getCode()) {
      SyntaxDocument doc = (SyntaxDocument) sc.getDocument();
      if (!hasSketchChangedListener(doc)) {
        doc.addDocumentListener(sketchChangedListener);
      }
    }
  }


  boolean hasSketchChangedListener(SyntaxDocument doc) {
    if (doc != null && doc.getDocumentListeners() != null) {
      for (DocumentListener dl : doc.getDocumentListeners()) {
        if (dl.equals(sketchChangedListener)) {
          return true;
        }
      }
    }
    return false;
  }


  protected void checkForMissingImports() {
    // Atomic access
    CodeCheckResult lastCodeCheckResult = this.lastCodeCheckResult;

    if (Preferences.getBoolean(JavaMode.SUGGEST_IMPORTS_PREF)) {
      for (Problem p : lastCodeCheckResult.problems) {
        if(p.getIProblem().getID() == IProblem.UndefinedType) {
          String args[] = p.getIProblem().getArguments();
          if (args.length > 0) {
            String missingClass = args[0];
            Messages.log("Will suggest for type:" + missingClass);
            //astGenerator.suggestImports(missingClass);
          }
        }
      }
    }
  }


  protected ASTGenerator astGenerator;

  public ASTGenerator getASTGenerator() {
    return astGenerator;
  }


  protected SketchChangedListener sketchChangedListener;
  protected class SketchChangedListener implements DocumentListener{

    private SketchChangedListener(){
    }

    @Override
    public void insertUpdate(DocumentEvent e) {
      if (JavaMode.errorCheckEnabled) {
        request();
        //log("doc insert update, man error check..");
      }
    }

    @Override
    public void removeUpdate(DocumentEvent e) {
      if (JavaMode.errorCheckEnabled){
        request();
        //log("doc remove update, man error check..");
      }
    }

    @Override
    public void changedUpdate(DocumentEvent e) {
      if (JavaMode.errorCheckEnabled){
        request();
        //log("doc changed update, man error check..");
      }
    }

  }

  public static class CodeCheckResult {

    boolean syntaxErrors;
    boolean containsErrors;

    CompilationUnit compilationUnit;

    String sourceCode;
    int sourceCodeOffset;

    List<Problem> problems = new ArrayList();

  }

  protected CodeCheckResult checkCode() {

    CodeCheckResult result = new CodeCheckResult();

    result.sourceCode = preprocessCode();
    result.sourceCodeOffset = 0;

    char[] sourceCodeArray = result.sourceCode.toCharArray();


    IProblem[] problems;

    {{ // SYNTAX CHECK

      result.syntaxErrors = true;
      result.containsErrors = true;

      parser.setSource(sourceCodeArray);
      parser.setKind(ASTParser.K_COMPILATION_UNIT);

      Map<String, String> options = JavaCore.getOptions();
      JavaCore.setComplianceOptions(JavaCore.VERSION_1_8, options);
      options.put(JavaCore.COMPILER_SOURCE, JavaCore.VERSION_1_8);
      options.put(JavaCore.COMPILER_DOC_COMMENT_SUPPORT, JavaCore.ENABLED);
      parser.setCompilerOptions(options);
      parser.setStatementsRecovery(true);

      result.compilationUnit = (CompilationUnit) parser.createAST(null);

      // Store errors returned by the ast parser
      problems = result.compilationUnit.getProblems();

      if (problems.length == 0) {
        result.syntaxErrors = false;
        result.containsErrors = false;
      } else {
        result.syntaxErrors = true;
        result.containsErrors = true;
      }

    }}

    // No syntax errors, proceed for compilation check, Stage 2.
    if (problems.length == 0 && !editor.hasJavaTabs()) {
      String sourceCode = xqpreproc.handle(result.sourceCode, programImports);
      prepareCompilerClasspath();

      {{ // COMPILE CHECK

        parser.setSource(sourceCodeArray);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);

        Map<String, String> options = JavaCore.getOptions();
        JavaCore.setComplianceOptions(JavaCore.VERSION_1_8, options);
        options.put(JavaCore.COMPILER_SOURCE, JavaCore.VERSION_1_8);
        options.put(JavaCore.COMPILER_DOC_COMMENT_SUPPORT, JavaCore.ENABLED);
        parser.setCompilerOptions(options);
        parser.setStatementsRecovery(true);

        result.compilationUnit = (CompilationUnit) parser.createAST(null);
        result.sourceCode = sourceCode;
        result.sourceCodeOffset = 1;

        // Currently (Sept, 2012) I'm using Java's reflection api to load the
        // CompilationChecker class(from CompilationChecker.jar) that houses the
        // Eclispe JDT compiler, and call its getErrorsAsObj method to obtain
        // errors. This way, I'm able to add the paths of contributed libraries
        // to the classpath of CompilationChecker, dynamically. The eclipse compiler
        // needs all referenced libraries in the classpath. Totally a hack. If you find
        // a better method, do let me know.

        try {
          // NOTE TO SELF: If classpath contains null Strings
          // URLClassLoader shoots NPE bullets.

          // If imports have changed, reload classes with new classpath.
          if (loadCompClass) {
            classPath = new URL[classpathJars.size()];
            classpathJars.toArray(classPath);

            compilationChecker = null;
            classLoader = null;
            System.gc();
            // log("CP Len -- " + classpath.length);
            classLoader = new URLClassLoader(classPath);
            compilationChecker = new CompilationChecker();
            loadCompClass = false;
          }

//          for(URL cpUrl: classPath) {
//            Messages.log("CP jar: " + cpUrl.getPath());
//          }

          if (compilerSettings == null) {
            prepareCompilerSetting();
          }

          problems = compilationChecker.getErrors(className, sourceCode,
                                                  compilerSettings, classLoader);
        } catch (Exception e) {
          System.err.println("compileCheck() problem." + e);
          e.printStackTrace();
          cancel();
        } catch (NoClassDefFoundError e) {
          e.printStackTrace();
          cancel();
        }
      }}
    }

    if (problems != null) {
      for (IProblem problem : problems) {

        // Hide a useless error which is produced when a line ends with
        // an identifier without a semicolon. "Missing a semicolon" is
        // also produced and is preferred over this one.
        // (Syntax error, insert ":: IdentifierOrNew" to complete Expression)
        // See: https://bugs.eclipse.org/bugs/show_bug.cgi?id=405780
        if (problem.getMessage().contains("Syntax error, insert \":: IdentifierOrNew\"")) {
          continue;
        }

        int sourceLine = problem.getSourceLineNumber() - result.sourceCodeOffset;
        int[] a = calculateTabIndexAndLineNumber(sourceLine);

        Problem p = new Problem(problem, a[0], a[1]);
        if (p.isError()) {
          result.containsErrors = true; // set flag
        } else if (p.isWarning() && !JavaMode.warningsEnabled) {
          continue;
        }
        result.problems.add(p);
      }
    }

    synchronized (astGenerator) {
      astGenerator.buildAST(result.sourceCode, result.compilationUnit);
    }

    return result;
  }

  protected volatile CodeCheckResult lastCodeCheckResult = new CodeCheckResult();

  public boolean hasSyntaxErrors(){
    return lastCodeCheckResult.syntaxErrors;
  }

  public boolean hasErrors(){
    return lastCodeCheckResult.containsErrors;
  }

  public TreeMap<String, IProblem> tempErrorLog;

  protected URLClassLoader classLoader;


  /**
   * Calculates PDE Offsets from Java Offsets for Problems
   */
  private void calcPdeOffsetsForProbList(CodeCheckResult codeCheckResult) {
    try {
      PlainDocument javaSource = new PlainDocument();

      javaSource.insertString(0, codeCheckResult.sourceCode, null);
      // Code in pde tabs stored as PlainDocument
      List<Document> pdeTabs = new ArrayList<>();
      for (SketchCode sc : editor.getSketch().getCode()) {
        PlainDocument tab = new PlainDocument();
        if (editor.getSketch().getCurrentCode().equals(sc)) {
          tab.insertString(0, sc.getDocumentText(), null);
        } else {
          tab.insertString(0, sc.getProgram(), null);
        }
        pdeTabs.add(tab);
      }
      int pkgNameOffset = ("package " + className + ";\n").length();
      // package name is added only during compile check
      if (codeCheckResult.sourceCodeOffset == 0) {
        pkgNameOffset = 0;
      }

      for (Problem p : codeCheckResult.problems) {
        int prbStart = p.getIProblem().getSourceStart() - pkgNameOffset;
        int prbEnd = p.getIProblem().getSourceEnd() - pkgNameOffset;
        int javaLineNumber = p.getSourceLineNumber() - 1;
        // not sure if this is necessary [fry 150808]
        javaLineNumber -= codeCheckResult.sourceCodeOffset;
        // errors on the first line were setting this to -1 [fry 150808]
        if (javaLineNumber < 0) {
          javaLineNumber = 0;
        }
        Element lineElement =
          javaSource.getDefaultRootElement().getElement(javaLineNumber);
        if (lineElement == null) {
          Messages.log("calcPDEOffsetsForProbList(): " +
                   "Couldn't fetch Java line number " +
                   javaLineNumber + "\nProblem: " + p);
          p.setPDEOffsets(-1, -1);
          continue;
        }
        int lineStart = lineElement.getStartOffset();
        int lineLength = lineElement.getEndOffset() - lineStart;
        String javaLine = javaSource.getText(lineStart, lineLength);

        Document doc = pdeTabs.get(p.getTabIndex());
        Element pdeLineElement =
          doc.getDefaultRootElement().getElement(p.getLineNumber());
        if (pdeLineElement == null) {
          Messages.log("calcPDEOffsetsForProbList(): " +
                   "Couldn't fetch pde line number " +
                   javaLineNumber + "\nProblem: " + p);
          p.setPDEOffsets(-1,-1);
          continue;
        }
        int pdeLineStart = pdeLineElement.getStartOffset();
        int pdeLineLength = pdeLineElement.getEndOffset() - pdeLineStart;
        String pdeLine =
          pdeTabs.get(p.getTabIndex()).getText(pdeLineStart, pdeLineLength);
        OffsetMatcher ofm = new OffsetMatcher(pdeLine, javaLine);
        int pdeOffset =
          ofm.getPdeOffForJavaOff(prbStart - lineStart, prbEnd - prbStart + 1);
        p.setPDEOffsets(pdeOffset, pdeOffset + prbEnd - prbStart);
      }
    } catch (BadLocationException ble) {
      ble.printStackTrace();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }


  public CompilationUnit getLatestCU() {
    return lastCodeCheckResult.compilationUnit;
  }


  private int loadClassCounter = 0;

  public URLClassLoader getSketchClassLoader() {
    loadClassCounter++;
    if (loadClassCounter > 100) {
      loadClassCounter = 0;
      classLoader = null;
      System.gc();
      classLoader = new URLClassLoader(classPath);
    }
    return classLoader;
  }


  /**
   * Processes import statements to obtain class paths of contributed
   * libraries. This would be needed for compilation check. Also, adds
   * stuff(jar files, class files, candy) from the code folder. And it looks
   * messed up.
   */
  protected void prepareCompilerClasspath() {
    if (!loadCompClass) {
      return;
    }

    synchronized (classpathJars) {
      // log("1..");
      classpathJars = new ArrayList<URL>();
      String entry = "";
//      boolean codeFolderChecked = false;
      for (ImportStatement impstat : programImports) {
        String item = impstat.getImportName();
        int dot = item.lastIndexOf('.');
        entry = (dot == -1) ? item : item.substring(0, dot);

        entry = entry.substring(6).trim();
        // log("Entry--" + entry);
        if (ignorableImport(entry)) {
          // log("Ignoring: " + entry);
          continue;
        }
        Library library = null;

        // Try to get the library classpath and add it to the list
        try {
          library = editor.getMode().getLibrary(entry);
          String[] libraryPath =
            PApplet.split(library.getClassPath().substring(1).trim(),
                          File.pathSeparatorChar);
          for (String pathItem : libraryPath) {
            classpathJars.add(new File(pathItem).toURI().toURL());
          }
        } catch (Exception e) {
          Messages.log("Encountered " + e + " while adding library to classpath");
        }
      }


      // Look around in the code folder for jar files and them too
      if (editor.getSketch().hasCodeFolder()) {
        File codeFolder = editor.getSketch().getCodeFolder();

        // get a list of .jar files in the "code" folder
        // (class files in subfolders should also be picked up)
        String codeFolderClassPath = Util.contentsToClassPath(codeFolder);
//        codeFolderChecked = true;
        // huh? doesn't this mean .length() == 0? [fry]
        if (!codeFolderClassPath.equalsIgnoreCase("")) {
          Messages.log("Sketch has a code folder. Adding its jars");
          String codeFolderPath[] =
                  PApplet.split(codeFolderClassPath.substring(1).trim(),
                          File.pathSeparatorChar);
          try {
            for (String pathItem : codeFolderPath) {
              classpathJars.add(new File(pathItem).toURI().toURL());
              Messages.log("Addind cf jar: " + pathItem);
            }
          } catch (Exception e2) {
            e2.printStackTrace();
          }
        }
      }

      // Also add jars specified in mode's search path
      String searchPath = ((JavaMode) getEditor().getMode()).getSearchPath();
      if (searchPath != null) {
        String[] modeJars = PApplet.split(searchPath, File.pathSeparatorChar);
        for (String mj : modeJars) {
          try {
            classpathJars.add(new File(mj).toURI().toURL());
          } catch (MalformedURLException e) {
            e.printStackTrace();
          }
        }
      }
    }
    new Thread(new Runnable() {
      public void run() {
        synchronized (astGenerator) {
          astGenerator.loadJars(); // update jar file for completion lookup
        }
      }
    }).start();
  }


  /**
   * Ignore processing packages, java.*.*. etc.
   */
  protected boolean ignorableImport(String packageName) {
    return (packageName.startsWith("java.") ||
            packageName.startsWith("javax."));
  }


  protected boolean ignorableSuggestionImport(String impName) {
    String impNameLc = impName.toLowerCase();

    for (ImportStatement impS : programImports) {
      if (impNameLc.startsWith(impS.getPackageName().toLowerCase())) {
        return false;
      }
    }

    for (ImportStatement impS : codeFolderImports) {
      if (impNameLc.startsWith(impS.getPackageName().toLowerCase())) {
        return false;
      }
    }

    final String include = "include";
    final String exclude = "exclude";

    if (impName.startsWith("processing")) {
      if (JavaMode.suggestionsMap.get(include).contains(impName)) {
        return false;
      } else if (JavaMode.suggestionsMap.get(exclude).contains(impName)) {
        return true;
      }
    } else if (impName.startsWith("java")) {
      if (JavaMode.suggestionsMap.get(include).contains(impName)) {
        return false;
      }
    }

    return true;
  }


  /** Options for the JDT Compiler */
  protected Map<String, String> compilerSettings;


  /** Set compiler options for JDT Compiler */
  protected void prepareCompilerSetting() {
    compilerSettings = new HashMap<String, String>();

    compilerSettings.put(CompilerOptions.OPTION_LineNumberAttribute,
                         CompilerOptions.GENERATE);
    compilerSettings.put(CompilerOptions.OPTION_SourceFileAttribute,
                         CompilerOptions.GENERATE);
    compilerSettings.put(CompilerOptions.OPTION_Source,
                         CompilerOptions.VERSION_1_8);
    compilerSettings.put(CompilerOptions.OPTION_ReportUnusedImport,
                         CompilerOptions.IGNORE);
    compilerSettings.put(CompilerOptions.OPTION_ReportMissingSerialVersion,
                         CompilerOptions.IGNORE);
    compilerSettings.put(CompilerOptions.OPTION_ReportRawTypeReference,
                         CompilerOptions.IGNORE);
    compilerSettings.put(CompilerOptions.OPTION_ReportUncheckedTypeOperation,
                         CompilerOptions.IGNORE);
  }


  /**
   * Updates the error table in the Error Window.
   */
  public void updateErrorTable(List<Problem> problems) {
    try {
      ErrorTable table = editor.getErrorTable();
      table.clearRows();

//      String[][] errorData = new String[problemsList.size()][3];
//      int index = 0;
//      for (int i = 0; i < problemsList.size(); i++) {
      Sketch sketch = editor.getSketch();
      for (Problem p : problems) {
        String message = p.getMessage();
        if (Preferences.getBoolean(JavaMode.SUGGEST_IMPORTS_PREF)) {
          if (p.getIProblem().getID() == IProblem.UndefinedType) {
            String[] args = p.getIProblem().getArguments();
            if (args.length > 0) {
              String missingClass = args[0];
              String[] si;
              synchronized (astGenerator) {
                si = astGenerator.getSuggestImports(missingClass);
              }
              if (si != null && si.length > 0) {
                p.setImportSuggestions(si);
//                errorData[index][0] = "<html>" + p.getMessage() +
//                  " (<font color=#0000ff><u>Import Suggestions available</u></font>)</html>";
                message += " (double-click for suggestions)";
              }
            }
          }
        }

        table.addRow(p, message,
                     sketch.getCode(p.getTabIndex()).getPrettyName(),
                     Integer.toString(p.getLineNumber() + 1));
        // Added +1 because lineNumbers internally are 0-indexed

//        //TODO: This is temporary
//        if (tempErrorLog.size() < 200) {
//          tempErrorLog.put(p.getMessage(), p.getIProblem());
//        }

      }
//      table.updateColumns();

//      DefaultTableModel tm =
//        new DefaultTableModel(errorData, XQErrorTable.columnNames);
//      editor.updateTable(tm);

    } catch (Exception e) {
      Messages.loge("Exception at updateErrorTable()", e);
      e.printStackTrace();
      cancel();
    }
  }


  /**
   * Updates editor status bar, depending on whether the caret is on an error
   * line or not
   */
  public void updateEditorStatus() {
//    if (editor.getStatusMode() == EditorStatus.EDIT) return;

    // editor.statusNotice("Position: " +
    // editor.getTextArea().getCaretLine());
    if (JavaMode.errorCheckEnabled) {
      LineMarker errorMarker = editor.findError(editor.getTextArea().getCaretLine());
      if (errorMarker != null) {
        if (errorMarker.getType() == LineMarker.WARNING) {
          editor.statusMessage(errorMarker.getProblem().getMessage(),
                               EditorStatus.WARNING);
        } else {
          editor.statusMessage(errorMarker.getProblem().getMessage(),
                               EditorStatus.COMPILER_ERROR);
        }
      } else {
        editor.statusEmpty(); // No error, clear the status
      }
    }

//    // This line isn't an error line anymore, so probably just clear it
//    if (editor.statusMessageType == JavaEditor.STATUS_COMPILER_ERR) {
//      editor.statusEmpty();
//      return;
//    }
  }


  /**
   * Maps offset from java code to pde code. Returns a bunch of offsets as array
   *
   * @param line
   *          - line number in java code
   * @param offset
   *          - offset from the start of the 'line'
   * @return int[0] - tab number, int[1] - line number in the int[0] tab, int[2]
   *         - line start offset, int[3] - offset from line start. int[2] and
   *         int[3] are on TODO
   */
  protected int[] JavaToPdeOffsets(int line, int offset) {
    int codeIndex = 0;

    int x = line - mainClassOffset;
    if (x < 0) {
      // log("Negative line number "
      // + problem.getSourceLineNumber() + " , offset "
      // + mainClassOffset);
      x = line - 2; // Another -1 for 0 index
      if (x < programImports.size() && x >= 0) {
        ImportStatement is = programImports.get(x);
        // log(is.importName + ", " + is.tab + ", "
        // + is.lineNumber);
        return new int[] { is.getTab(), is.getLineNumber() };
      } else {

        // Some seriously ugly stray error, just can't find the source
        // line! Simply return first line for first tab.
        return  new int[] { 0, 1 };
      }

    }

    try {
      for (SketchCode sc : editor.getSketch().getCode()) {
        if (sc.isExtension("pde")) {
          int len = 0;
          if (editor.getSketch().getCurrentCode().equals(sc)) {
            len = Util.countLines(sc.getDocumentText()) + 1;
          } else {
            len = Util.countLines(sc.getProgram()) + 1;
          }

          // log("x,len, CI: " + x + "," + len + ","
          // + codeIndex);

          if (x >= len) {

            // We're in the last tab and the line count is greater
            // than the no.
            // of lines in the tab,
            if (codeIndex >= editor.getSketch().getCodeCount() - 1) {
              // log("Exceeds lc " + x + "," + len
              // + problem.toString());
              // x = len
              x = editor.getSketch().getCode(codeIndex)
                  .getLineCount();
              // TODO: Obtain line having last non-white space
              // character in the code.
              break;
            } else {
              x -= len;
              codeIndex++;
            }
          } else {

            if (codeIndex >= editor.getSketch().getCodeCount()) {
              codeIndex = editor.getSketch().getCodeCount() - 1;
            }
            break;
          }

        }
      }
    } catch (Exception e) {
      System.err.println("Error inside ErrorCheckerService.JavaToPdeOffset()");
      e.printStackTrace();
    }
    return new int[] { codeIndex, x };
  }


  protected String getPdeCodeAtLine(int tab, int linenumber){
    if(linenumber < 0) return null;
    editor.getSketch().setCurrentCode(tab);
    return editor.getTextArea().getLineText(linenumber);
  }


  /**
   * Calculates the tab number and line number of the error in that particular
   * tab. Provides mapping between pure java and pde code.
   *
   * @param javalineNumber
   *            - int
   * @return int[0] - tab number, int[1] - line number
   */
  protected int[] calculateTabIndexAndLineNumber(int javalineNumber) {
    // String[] lines = {};// = PApplet.split(sourceString, '\n');
    int codeIndex = 0;

    int x = javalineNumber - mainClassOffset;
    if (x < 0) {
      // log("Negative line number "
      // + problem.getSourceLineNumber() + " , offset "
      // + mainClassOffset);
      x = javalineNumber - 2; // Another -1 for 0 index
      if (x < programImports.size() && x >= 0) {
        ImportStatement is = programImports.get(x);
        // log(is.importName + ", " + is.tab + ", "
        // + is.lineNumber);
        return new int[] { is.getTab(), is.getLineNumber() };
      } else {

        // Some seriously ugly stray error, just can't find the source
        // line! Simply return first line for first tab.
        return new int[] { 0, 1 };
      }

    }

    try {
      for (SketchCode sc : editor.getSketch().getCode()) {
        if (sc.isExtension("pde")) {
          int len = 0;
          if (editor.getSketch().getCurrentCode().equals(sc)) {
            len = Util.countLines(sc.getDocumentText()) + 1;
          } else {
            len = Util.countLines(sc.getProgram()) + 1;
          }

          // log("x,len, CI: " + x + "," + len + ","
          // + codeIndex);

          if (x >= len) {

            // We're in the last tab and the line count is greater
            // than the no.
            // of lines in the tab,
            if (codeIndex >= editor.getSketch().getCodeCount() - 1) {
              // log("Exceeds lc " + x + "," + len
              // + problem.toString());
              // x = len
              x = editor.getSketch().getCode(codeIndex)
                  .getLineCount();
              // TODO: Obtain line having last non-white space
              // character in the code.
              break;
            } else {
              x -= len;
              codeIndex++;
            }
          } else {
            if (codeIndex >= editor.getSketch().getCodeCount()) {
              codeIndex = editor.getSketch().getCodeCount() - 1;
            }
            break;
          }
        }
      }
    } catch (Exception e) {
      System.err.println("Things got messed up in ErrorCheckerService.calculateTabIndexAndLineNumber()");
    }
    return new int[] { codeIndex, x };
  }


  /**
   * Returns line number of corresponding java source
   */
  protected int getJavaLineNumFromPDElineNum(int tab, int pdeLineNum){
    int jLineNum = programImports.size() + 1;
    for (int i = 0; i < tab; i++) {
      SketchCode sc = editor.getSketch().getCode(i);
      int len = Util.countLines(sc.getProgram()) + 1;
      jLineNum += len;
    }
    return jLineNum;
  }


  /**
   * Fetches code from the editor tabs and pre-processes it into parsable pure
   * java source. And there's a difference between parsable and compilable.
   * XQPrerocessor.java makes this code compilable. <br>
   * Handles: <li>Removal of import statements <li>Conversion of int(),
   * char(), etc to PApplet.parseInt(), etc. <li>Replacing '#' with 0xff for
   * color representation<li>Converts all 'color' datatypes to int
   * (experimental) <li>Appends class declaration statement after determining
   * the mode the sketch is in - ACTIVE or STATIC
   *
   * @return String - Pure java representation of PDE code. Note that this
   *         code is not yet compile ready.
   */
  protected String preprocessCode() {
    programImports = new ArrayList<>();
    StringBuilder rawCode = new StringBuilder();
    final Sketch sketch = editor.getSketch();
    try {
      for (SketchCode sc : sketch.getCode()) {
        if (sc.isExtension("pde")) {

          try {
            if (sketch.getCurrentCode().equals(sc)) {
              rawCode.append(scrapImportStatements(sc.getDocumentText(),
                                                   sketch.getCodeIndex(sc)));
            } else {
              rawCode.append(scrapImportStatements(sc.getProgram(),
                                                   sketch.getCodeIndex(sc)));
            }
            rawCode.append('\n');
          } catch (Exception e) {
            e.printStackTrace();
          }
          rawCode.append('\n');
        }
      }

    } catch (Exception e) {
      Messages.log("Exception in preprocessCode()");
    }
    String sourceAlt = rawCode.toString();
    // Replace comments with whitespaces
    // sourceAlt = scrubComments(sourceAlt);

    // Find all int(*), replace with PApplet.parseInt(*)

    // \bint\s*\(\s*\b , i.e all exclusive "int("

    String dataTypeFunc[] = { "int", "char", "float", "boolean", "byte" };

    for (String dataType : dataTypeFunc) {
      String dataTypeRegexp = "\\b" + dataType + "\\s*\\(";
      Pattern pattern = Pattern.compile(dataTypeRegexp);
      Matcher matcher = pattern.matcher(sourceAlt);

      // while (matcher.find()) {
      // System.out.print("Start index: " + matcher.start());
      // log(" End index: " + matcher.end() + " ");
      // log("-->" + matcher.group() + "<--");
      // }
      sourceAlt = matcher.replaceAll("PApplet.parse"
        + Character.toUpperCase(dataType.charAt(0))
        + dataType.substring(1) + "(");

    }

    // Find all #[web color] and replace with 0xff[webcolor]
    // Should be 6 digits only.
    final String webColorRegexp = "#{1}[A-F|a-f|0-9]{6}\\W";
    Pattern webPattern = Pattern.compile(webColorRegexp);
    Matcher webMatcher = webPattern.matcher(sourceAlt);
    while (webMatcher.find()) {
      // log("Found at: " + webMatcher.start());
      String found = sourceAlt.substring(webMatcher.start(),
                                         webMatcher.end());
      // log("-> " + found);
      sourceAlt = webMatcher.replaceFirst("0xff" + found.substring(1));
      webMatcher = webPattern.matcher(sourceAlt);
    }

    // Replace all color data types with int
    // Regex, Y U SO powerful?
    final String colorTypeRegex = "color(?![a-zA-Z0-9_])(?=\\[*)(?!(\\s*\\())";
    Pattern colorPattern = Pattern.compile(colorTypeRegex);
    Matcher colorMatcher = colorPattern.matcher(sourceAlt);
    sourceAlt = colorMatcher.replaceAll("int");

    checkForChangedImports();

    className = (editor == null) ?
      "DefaultClass" : editor.getSketch().getName();

    // Check whether the code is being written in STATIC mode
    try {
      String uncommented = PdePreprocessor.scrubComments(sourceAlt);
      mode = PdePreprocessor.parseMode(uncommented);
    } catch (RuntimeException r) {
      String uncommented = PdePreprocessor.scrubComments(sourceAlt + "*/");
      mode = PdePreprocessor.parseMode(uncommented);
    }

    StringBuilder sb = new StringBuilder();

    // Imports
    sb.append(xqpreproc.prepareImports(programImports));

    // Header
    if (mode != PdePreprocessor.Mode.JAVA) {
      sb.append("public class " + className + " extends PApplet {\n");
      if (mode == PdePreprocessor.Mode.STATIC) {
        sb.append("public void setup() {\n");
      }
    }

    // Grab the offset before adding contents of the editor
    mainClassOffset = 1;
    for (int i = 0; i < sb.length(); i++) {
      if (sb.charAt(i) == '\n') {
        mainClassOffset++;
      }
    }

    // Editor content
    sb.append(sourceAlt);

    // Footer
    if (mode != PdePreprocessor.Mode.JAVA) {
      if (mode == PdePreprocessor.Mode.STATIC) {
        // no noLoop() here so it does not tell you
        // "can't invoke noLoop() on obj" when you type "obj."
        sb.append("\n}");
      }
      sb.append("\n}");
    }

    return substituteUnicode(sb.toString());
  }


  /**
   * Now defunct.
   * The super method that highlights any ASTNode in the pde editor =D
   * @param awrap
   * @return true - if highlighting happened correctly.
   */
  private boolean highlightNode(ASTNodeWrapper awrap){
    Messages.log("Highlighting: " + awrap);
    try {
      int pdeoffsets[] = awrap.getPDECodeOffsets(this);
      int javaoffsets[] = awrap.getJavaCodeOffsets(this);
      Messages.log("offsets: " +pdeoffsets[0] + "," +
          pdeoffsets[1]+ "," +javaoffsets[1]+ "," +
          javaoffsets[2]);
      scrollToErrorLine(editor, pdeoffsets[0],
                                            pdeoffsets[1],javaoffsets[1],
                                            javaoffsets[2]);
      return true;
    } catch (Exception e) {
      Messages.loge("Scrolling failed for " + awrap);
      // e.printStackTrace();
    }
    return false;
  }

  public boolean highlightNode(ASTNode node){
    ASTNodeWrapper awrap = new ASTNodeWrapper(node);
    return highlightNode(awrap);
  }

  /**
   * Scrolls to the error source in code. And selects the line text. Used by
   * XQErrorTable and ErrorBar
   *
   * @param errorIndex
   *            - index of error
   */
  public void scrollToErrorLine(int errorIndex) {
    if (editor == null) {
      return;
    }

    // Atomic access
    CodeCheckResult lastCodeCheckResult = this.lastCodeCheckResult;

    if (errorIndex < lastCodeCheckResult.problems.size() && errorIndex >= 0) {
      Problem p = lastCodeCheckResult.problems.get(errorIndex);
      scrollToErrorLine(p);
    }
  }

  public void scrollToErrorLine(Problem p) {
    if (editor == null) {
      return;
    }
    if (p == null)
      return;
    try {
      if(p.getPDELineStartOffset() == -1 || p.getPDELineStopOffset() == -1){
        // bad offsets, don't highlight, just scroll.
        editor.toFront();
        editor.getSketch().setCurrentCode(p.getTabIndex());
      }
      else {
        astGenerator.highlightPDECode(p.getTabIndex(),
                                      p.getLineNumber(),
                                      p.getPDELineStartOffset(),
                                      (p.getPDELineStopOffset()
                                          - p.getPDELineStartOffset() + 1));
      }

      // scroll, but within boundaries
      // It's also a bit silly that if parameters to scrollTo() are out of range,
      // a BadLocation Exception is thrown internally and caught in JTextArea AND
      // even the stack trace gets printed! W/o letting me catch it later! SMH
      // That's because 1) you can prevent it by not causing the BLE,
      // and 2) there are so many JEditSyntax bugs that actually throwing the
      // exception all the time would cause the editor to shut down over
      // trivial/recoverable quirks. It's the least bad option. [fry]
      final Document doc = editor.getTextArea().getDocument();
      final int lineCount = Util.countLines(doc.getText(0, doc.getLength()));
      if (p.getLineNumber() < lineCount && p.getLineNumber() >= 0) {
        editor.getTextArea().scrollTo(p.getLineNumber(), 0);
      }
      editor.repaint();

    } catch (Exception e) {
      Messages.loge("Error while selecting text in scrollToErrorLine(), for problem: " + p, e);
    }
    // log("---");
  }

  /**
   * Static method for scroll to a particular line in the PDE. Also highlights
   * the length of the text. Requires the editor instance as arguement.
   *
   * @param edt
   * @param tabIndex
   * @param lineNoInTab
   *          - line number in the corresponding tab
   * @param lineStartOffset
   *          - selection start offset(from line start non-whitespace offset)
   * @param length
   *          - length of selection
   * @return - true, if scroll was successful
   */
  public static boolean scrollToErrorLine(Editor edt, int tabIndex, int lineNoInTab, int lineStartOffset, int length) {
    if (edt == null) {
      return false;
    }
    try {
      edt.toFront();
      edt.getSketch().setCurrentCode(tabIndex);
      int lsno = edt.getTextArea()
          .getLineStartNonWhiteSpaceOffset(lineNoInTab - 1) + lineStartOffset;
      edt.setSelection(lsno, lsno + length);
      edt.getTextArea().scrollTo(lineNoInTab - 1, 0);
      edt.repaint();
      Messages.log(lineStartOffset + " LSO,len " + length);

    } catch (Exception e) {
      System.err.println(e
          + " : Error while selecting text in static scrollToErrorLine()");
      e.printStackTrace();
      return false;
    }
    return true;
  }

  /**
   * Checks if import statements in the sketch have changed. If they have,
   * compiler classpath needs to be updated.
   */
  protected void checkForChangedImports() {
//     log("Imports: " + programImports.size() +
//     " Prev Imp: "
//     + previousImports.size());
    if (programImports.size() != previousImports.size()) {
      // log(1);
      loadCompClass = true;
      previousImports = programImports;
    } else {
      for (int i = 0; i < programImports.size(); i++) {
        if (!programImports.get(i).getImportName().equals(previousImports
            .get(i).getImportName())) {
          // log(2);
          loadCompClass = true;
          previousImports = programImports;
          break;
        }
      }
    }
    // log("load..? " + loadCompClass);
  }

  protected int pdeImportsCount;

  public int getPdeImportsCount() {
    return pdeImportsCount;
  }

  /**
   * Removes import statements from tabSource, replaces each with white spaces
   * and adds the import to the list of program imports
   *
   * @param tabProgram
   *            - Code in a tab
   * @param tabNumber
   *            - index of the tab
   * @return String - Tab code with imports replaced with white spaces
   */
  protected String scrapImportStatements(String tabProgram, int tabNumber) {
    //TODO: Commented out imports are still detected as main imports.
    pdeImportsCount = 0;
    String tabSource = tabProgram;
    do {
      // log("-->\n" + sourceAlt + "\n<--");
      String[] pieces = PApplet.match(tabSource, IMPORT_REGEX);

      // Stop the loop if we've removed all the import lines
      if (pieces == null) {
        break;
      }

      String piece = pieces[1] + pieces[2] + pieces[3];
      int len = piece.length(); // how much to trim out

      // programImports.add(piece); // the package name

      // find index of this import in the program
      int idx = tabSource.indexOf(piece);
      // System.out.print("Import -> " + piece);
      // log(" - "
      // + Base.countLines(tabSource.substring(0, idx)) + " tab "
      // + tabNumber);
      int lineCount = Util.countLines(tabSource.substring(0, idx));
      programImports.add(new ImportStatement(piece, tabNumber, lineCount));
      // Remove the import from the main program
      // Substitute with white spaces
      String whiteSpace = "";
      for (int j = 0; j < piece.length(); j++) {
        whiteSpace += " ";
      }
      tabSource = tabSource.substring(0, idx) + whiteSpace
        + tabSource.substring(idx + len);
      pdeImportsCount++;
    } while (true);
    // log(tabSource);
    return tabSource;
  }

  /**
   * Replaces non-ascii characters with their unicode escape sequences and
   * stuff. Used as it is from
   * processing.src.processing.mode.java.preproc.PdePreprocessor
   *
   * @param program
   *            - Input String containing non ascii characters
   * @return String - Converted String
   */
  public static String substituteUnicode(String program) {
    // check for non-ascii chars (these will be/must be in unicode format)
    char p[] = program.toCharArray();
    int unicodeCount = 0;
    for (int i = 0; i < p.length; i++) {
      if (p[i] > 127) {
        unicodeCount++;
      }
    }
    if (unicodeCount == 0) {
      return program;
    }
    // if non-ascii chars are in there, convert to unicode escapes
    // add unicodeCount * 5.. replacing each unicode char
    // with six digit uXXXX sequence (xxxx is in hex)
    // (except for nbsp chars which will be a replaced with a space)
    int index = 0;
    char p2[] = new char[p.length + unicodeCount * 5];
    for (int i = 0; i < p.length; i++) {
      if (p[i] < 128) {
        p2[index++] = p[i];
      } else if (p[i] == 160) { // unicode for non-breaking space
        p2[index++] = ' ';
      } else {
        int c = p[i];
        p2[index++] = '\\';
        p2[index++] = 'u';
        char str[] = Integer.toHexString(c).toCharArray();
        // add leading zeros, so that the length is 4
        // for (int i = 0; i < 4 - str.length; i++) p2[index++] = '0';
        for (int m = 0; m < 4 - str.length; m++)
          p2[index++] = '0';
        System.arraycopy(str, 0, p2, index, str.length);
        index += str.length;
      }
    }
    return new String(p2, 0, index);
  }

  public void handleErrorCheckingToggle() {
    if (!JavaMode.errorCheckEnabled) {
      Messages.log(editor.getSketch().getName() + " Error Checker paused.");
      //editor.clearErrorPoints();
      editor.getErrorPoints().clear();
      lastCodeCheckResult.problems.clear();
      updateErrorTable(Collections.<Problem>emptyList());
      updateEditorStatus();
      editor.getTextArea().repaint();
      editor.repaintErrorBar();
    } else {
      Messages.log(editor.getSketch().getName() + " Error Checker resumed.");
      request();
    }
  }


  public JavaEditor getEditor() {
    return editor;
  }

  public ArrayList<ImportStatement> getProgramImports() {
    return programImports;
  }
}
