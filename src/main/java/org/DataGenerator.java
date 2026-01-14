package org;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.*;
import java.util.logging.Formatter;

import org.rip.RipParser;
import org.utils.MutationConfig;

import static org.utils.ExcelUtils.readExcel;

public class DataGenerator {

    private static final Logger logger = Logger.getLogger(DataGenerator.class.getName());
    private static String operatorName;
    private static String lineNo;
    private static String methodName;
    private static String className;
    private static String className_F;
    private static String mutationStatement;
    private static String packageName;
    private static String projectName;
    private static String filepath;
    private static String graphPath;
    private static boolean rewrite = true; // Whether to overwrite existing graph files
    private static String logType;
    private static final Set<String> stringSet = new HashSet<>();
    private static boolean logAppend;

    public static void main(String[] args) {
        logAppend = false;
        String mutantPath = "../EquivDetect/dataset/mutant_statistic_1.xlsx";
        sootMutants(mutantPath);
        // test();
    }

    private static void sootMutants(String filePath) {
        try {
            String fileName = new File(filePath).getName();
            int dotIndex = fileName.lastIndexOf('.');
            String baseName = (dotIndex > 0) ? fileName.substring(0, dotIndex) : fileName;
            String logFile = "logs/" + baseName + ".log";
            setupLogger(logFile);
            List<List<Object>> excelData = readExcelFile(filePath);
            for (int i = 1; i < excelData.size(); i++) {
                MutationConfig config = new MutationConfig();
                config.operator = (String) excelData.get(i).get(0);
                config.lineNo = (String) excelData.get(i).get(1);
                config.methodName = (String) excelData.get(i).get(2);
                config.className = (String) excelData.get(i).get(3);
                config.classNameF = (String) excelData.get(i).get(4);
                config.mutationStatement = (String) excelData.get(i).get(5);
                config.packageName = (String) excelData.get(i).get(6);
                config.projectName = (String) excelData.get(i).get(7);
                config.filepath = new File((String) excelData.get(i).get(8)).getParent().replace("\\", "/");
                config.filepath = config.filepath.replace("//?/", "");
                rewrite = true;
                System.out.print(i + " th: ");
                System.out.println(config.filepath);
                // sootOriginalProgram(filepath, config);
                if (rewrite || graphNotExist(graphPath)) {
                    run(config);
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to set up logger: " + e.getMessage());
        }
    }

    private static void sootOriginalProgram(String filePath, MutationConfig config) {
        filepath = Paths.get(filePath).getParent().getParent().getParent().toString() + "/original";
        // Prevent duplicate generation
        String curHash = filepath + "/" + methodName;
        if (!stringSet.contains(curHash)) {
            graphPath = filepath + "/" + methodName + "/graph";
            logType = "mutant";
            String absolutePath = new File(filepath + "/" + methodName).getAbsolutePath().replace("\\", "/");
            ;
            System.out.println("Soot for: " + absolutePath);
            if (rewrite || graphNotExist(graphPath)) {
                run(config);
                stringSet.add(curHash);
            }
        }
        logType = "original";
        filepath = filePath; // Restore mutant file path
        graphPath = filepath + "/graph"; // Restore graph output path
        String absolutePath = new File(filepath).getAbsolutePath().replace("\\", "/");
        ;
        System.out.println("Soot for: " + absolutePath);
    }

    public static boolean graphNotExist(String graphPath) {
        String[] filesToCheck = { "output.jsonl" };

        boolean allFilesExist = false;

        // Check if each file exists
        for (String fileName : filesToCheck) {
            Path filePath = Paths.get(graphPath, fileName);
            if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
                allFilesExist = true;
                break;
            }
        }
        return allFilesExist;
    }

    private static List<List<Object>> readExcelFile(String filePath) {
        try {
            return readExcel(filePath, 0);
        } catch (IOException e) {
            e.printStackTrace();
            return Collections.emptyList();
        }
    }

    private static void run(MutationConfig config) {
        try {
            // Analyze class and method
            RipParser parser = new RipParser(config);
            String json = parser.analyzePairToJson(); // Instance method (not static)
            // System.out.println(json);
        } catch (Exception e) {
            System.err.println(
                    "Analysis failed for " + config.className + "####" + config.methodName + "####" + e.getMessage());
            logger.log(Level.SEVERE, "Analysis failed for ####"
                    + "mutant" + "####" + config.operator + "####" + config.lineNo + "####" + config.methodName + "####"
                    + config.mutationStatement + "####" + config.className + "####" + config.classNameF + "####"
                    + config.packageName + "####" + config.projectName + "####" + config.filepath + "####"
                    + e.getMessage());
            e.printStackTrace();
        } finally {
            // Reset Soot state
        }
    }

    private static void setupLogger(String logFile) throws IOException {
        Files.createDirectories(Paths.get("logs"));

        FileHandler fileHandler = new FileHandler(logFile, logAppend);
        fileHandler.setFormatter(new SimpleFormatterWithoutPrefix());

        Logger rootLogger = Logger.getLogger("");
        rootLogger.addHandler(fileHandler);
        rootLogger.setLevel(Level.SEVERE);

        // Remove default console output
        Handler[] handlers = rootLogger.getHandlers();
        for (Handler handler : handlers) {
            if (handler instanceof java.util.logging.ConsoleHandler) {
                rootLogger.removeHandler(handler);
            }
        }

        logger.info("Logger initialized.");
    }

    private static class SimpleFormatterWithoutPrefix extends Formatter {
        @Override
        public String format(LogRecord record) {
            return record.getLevel() + ": " + record.getMessage() + "\n";
        }
    }
}
