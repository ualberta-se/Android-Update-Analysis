package ca.ualberta.mehran.androidevolution.repositories;


import ca.ualberta.mehran.androidevolution.Utils;
import ca.ualberta.mehran.androidevolution.mapping.EvolutionAnalyser;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.FileFilter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

import static ca.ualberta.mehran.androidevolution.Utils.log;
import static ca.ualberta.mehran.androidevolution.Utils.runSystemCommand;

public class CommitBasedAutomation {


    private static final String OUTPUT_PATH = "output";
    private static final String CSV_INPUT_PATH = "input/csv";
    private static final String REPOS_PATH = "input/repos";

    private static final String VERSION_LINE_PREFIX = "versions:";
    private static final boolean DEBUG = true;

    public static void main(String[] args) {

        String sourcererCCPath = null;

        if (args != null && args.length > 0) {
            sourcererCCPath = args[0];
        } else {
            throw new RuntimeException("SourcererCC path not provided");
        }

        if (DEBUG) {
            new CommitBasedAutomation().targetedRun(sourcererCCPath, "android_packages_apps_Calculator", "https://github.com/LineageOS/android_packages_apps_Calculator", "5c5a1623a41cc100a744aca4931466eb3543ffcc", "src");
        } else {
            new CommitBasedAutomation().run(sourcererCCPath);
        }
    }

    private void targetedRun(String sourcererCCPath, String name, String repoUrl, String commitHash, String subsystemFolder) {
        Repository repository = new Repository(name, repoUrl);
        String generalRepoPath = new File(REPOS_PATH, repository.name).getAbsolutePath();
        File gitRepoPath = new File(generalRepoPath, "CM");
        gitRepoPath.mkdirs();
        checkoutRepository(repository, gitRepoPath);

        Subsystem subsystem = new Subsystem(repository.name, subsystemFolder, subsystemFolder, gitRepoPath.getAbsolutePath());
        AospMergeCommit mergeCommit = populateMergeCommit(gitRepoPath.getAbsolutePath(), commitHash);

        processSubsystem(subsystem, repository, mergeCommit, "c", generalRepoPath, gitRepoPath,
                null, new EvolutionAnalyser(), sourcererCCPath);
    }

    public void run(String sourcererCCPath) {

        // Project input CSV files should be copied to CSV_INPUT_PATH/PROJECT_NAME.
        for (File projectInputCSVsDir : getProjectInputCSVsPath()) {
            String projectName = projectInputCSVsDir.getName();
            File[] inputCsvFiles = getProjectInputCsvFiles(projectInputCSVsDir);

            Set<Repository> pairedRepositories = new HashSet<>();

            for (File inputCsvFile : inputCsvFiles) {
                if (!isValidInputCsvFile(inputCsvFile)) continue;
                readInputCsvFile(inputCsvFile, pairedRepositories);
            }

            Utils.writeToFile(OUTPUT_PATH + "/repos_" + projectName + ".txt", String.valueOf(pairedRepositories.size()));
            log("Going to analyze " + pairedRepositories.size() + " repositories");
            prepareForAnalysis(projectName, pairedRepositories, sourcererCCPath);
        }

    }

    private void prepareForAnalysis(String projectName, Set<Repository> pairedRepositories, String sourcererCCPath) {

        EvolutionAnalyser evolutionAnalyser = new EvolutionAnalyser();
        String outputPath = new File(OUTPUT_PATH, projectName).getAbsolutePath();

        int repositoryIndex = 0;
        for (Repository repository : pairedRepositories) {
            repositoryIndex++;
            log(String.format("Working on %s... (%d/%d)", repository, repositoryIndex, pairedRepositories.size()));

            processRepository(projectName, repository, outputPath, evolutionAnalyser, sourcererCCPath);
        }
    }

    private void processRepository(String projectName, Repository repository, String outputPath,
                                   EvolutionAnalyser evolutionAnalyser, String sourcererCCPath) {
        String generalRepoPath = new File(REPOS_PATH, repository.name).getAbsolutePath();
        File gitRepoPath = new File(generalRepoPath, projectName);
        gitRepoPath.mkdirs();
        checkoutRepository(repository, gitRepoPath);

        // Find merge commits
        String[] branches = new String[]{"lineage-15.1", "lineage-15.0", "cm-14.1", "cm-14.0", "cm-13.0", "cm-12.1", "cm-12.0", "cm-11.0"};

        int branchIndex = 0;
        for (String branch : branches) {
            branchIndex++;
            log(String.format("Switching to %s... (%d/%d)", branch, branchIndex, branches.length));

            processBranch(branch, repository, generalRepoPath, gitRepoPath, outputPath, evolutionAnalyser,
                    sourcererCCPath);
        }
    }

    private void processBranch(String branch, Repository repository, String generalRepoPath,
                               File gitRepoPath, String outputPath,
                               EvolutionAnalyser evolutionAnalyser, String sourcererCCPath) {
        if (gitChangeBranch(gitRepoPath.getAbsolutePath(), branch)) {
            List<AospMergeCommit> mergeCommits = findUpstreamMergeCommits(gitRepoPath.getAbsolutePath());
            log("Found " + mergeCommits.size() + " merge commits.");

            int commitIndex = 0;
            for (AospMergeCommit mergeCommit : mergeCommits) {
                commitIndex++;
                log(String.format("Trying commit %.7s... (%d/%d)", mergeCommit.commitHash, commitIndex, mergeCommits.size()));

                processCommit(repository, mergeCommit, generalRepoPath, gitRepoPath, outputPath,
                        evolutionAnalyser, sourcererCCPath);
            }

        }
    }

    private void processCommit(Repository repository, AospMergeCommit mergeCommit, String generalRepoPath,
                               File gitRepoPath, String outputPath,
                               EvolutionAnalyser evolutionAnalyser, String sourcererCCPath) {
        boolean canAutomaticallyMerge = gitReplayAospMerge(gitRepoPath.getAbsolutePath(), mergeCommit);
        String mergeStatus = canAutomaticallyMerge ? "nc" : "c";

        List<Subsystem> subsystems = getSubsystemsInRepository(repository.name, gitRepoPath, mergeCommit);

        int subsystemIndex = 0;
        for (Subsystem subsystem : subsystems) {
            subsystemIndex++;
            log(String.format("Analysing subsystem %s... (%d/%d)", subsystem.repository + "/" + subsystem.name, subsystemIndex, subsystems.size()));

            processSubsystem(subsystem, repository, mergeCommit, mergeStatus, generalRepoPath,
                    gitRepoPath, outputPath, evolutionAnalyser, sourcererCCPath);
        }
    }

    private void processSubsystem(Subsystem subsystem, Repository repository, AospMergeCommit mergeCommit,
                                  String mergeStatus, String generalRepoPath, File gitRepoPath, String outputPath,
                                  EvolutionAnalyser evolutionAnalyser, String sourcererCCPath) {
        if (mergeCommit == null) {
            log("Invalid merge commit");
            return;
        }
        String analysisName = String.format("%s-%s-%s-%s", repository.name, subsystem.name, mergeCommit.commitHash, mergeStatus);

        File comparisonFolderParent = new File(generalRepoPath, analysisName);
        comparisonFolderParent.mkdir();

        // If output file for analysis exists, don't run the analysis.
        if (!DEBUG && new File(outputPath, analysisName + ".csv").exists()) {
            log("Analysis already performed; skipping.");
            removeFolder(comparisonFolderParent);
            return;
        }

        ComparisionFolder comparisionFolderAoAn = new ComparisionFolder(comparisonFolderParent.getAbsolutePath(),
                "AO", "AN");
        ComparisionFolder comparisionFolderAoCm = new ComparisionFolder(comparisonFolderParent.getAbsolutePath(),
                "AO", "CM");

        if (!gitResetToCommit(gitRepoPath.getAbsolutePath(), mergeCommit.commonAncestorCommit))
            return;
        copyFolder(new File(gitRepoPath.getAbsolutePath(), subsystem.relativePath).getAbsolutePath(), comparisionFolderAoAn.getOldVersionPath());
        copyFolder(new File(gitRepoPath.getAbsolutePath(), subsystem.relativePath).getAbsolutePath(), comparisionFolderAoCm.getOldVersionPath());

        if (!gitResetToCommit(gitRepoPath.getAbsolutePath(), mergeCommit.aospParentCommitHash))
            return;
        copyFolder(new File(gitRepoPath.getAbsolutePath(), subsystem.relativePath).getAbsolutePath(), comparisionFolderAoAn.getNewVersionPath());

        if (!gitResetToCommit(gitRepoPath.getAbsolutePath(), mergeCommit.cmParentCommitHash))
            return;
        copyFolder(new File(gitRepoPath.getAbsolutePath(), subsystem.relativePath).getAbsolutePath(), comparisionFolderAoCm.getNewVersionPath());

        try {
            evolutionAnalyser.run(analysisName, comparisionFolderAoAn.getPath(),
                    comparisionFolderAoAn.getOldVersionPath(), comparisionFolderAoAn.getNewVersionPath(),
                    comparisionFolderAoCm.getPath(), comparisionFolderAoCm.getOldVersionPath(),
                    comparisionFolderAoCm.getNewVersionPath(), sourcererCCPath, outputPath);
        } catch (Throwable e) {
            log("An exception occurred while analyzing " + analysisName + ": " + e.getMessage());
            for (StackTraceElement stackTraceElement : e.getStackTrace()) {
                log(stackTraceElement.toString());
            }
            e.printStackTrace();
        } finally {
            if (!DEBUG) {
                removeFolder(comparisonFolderParent);
            }
        }
    }

    private void readInputCsvFile(File inputCsvFile, Set<Repository> pairedRepositories) {
        try {
            Scanner input = new Scanner(inputCsvFile);
            while (input.hasNextLine()) {
                String line = input.nextLine().trim();
                if (line.equals("") || line.startsWith("!") || line.startsWith("#") || line.startsWith("/")) {
                    continue;
                } else if (line.toLowerCase().startsWith(VERSION_LINE_PREFIX)) {
//                    versions.add(new ComparisonVersions(line));
                } else if (line.split(",").length >= 3) {
                    String[] cells = line.split(",");
                    if (cells.length >= 3) {
                        Repository repository = new Repository(cells[0], cells[2]);
                        pairedRepositories.add(repository);
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private boolean isValidInputCsvFile(File inputCsvFile) {
        try {
            Scanner input = new Scanner(inputCsvFile);
            while (input.hasNextLine()) {
                String line = input.nextLine().trim();
                if (line.equals("") || line.startsWith("!") || line.startsWith("#") || line.startsWith("/")) {
                    continue;
                }
                if (line.toLowerCase().startsWith(VERSION_LINE_PREFIX)) {
                    continue;
                }
                if (line.split(",").length >= 3) {
                    continue;
                }
                return false;
            }
            return true;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return false;
    }

    private File[] getProjectInputCSVsPath() {
        File csvInputs = new File(CSV_INPUT_PATH);
        if (!csvInputs.exists() || !csvInputs.isDirectory()) {
            throw new RuntimeException(CSV_INPUT_PATH + " doesn't exist");
        }
        return csvInputs.listFiles(new FileFilter() {
            @Override
            public boolean accept(File pathname) {
                return pathname.isDirectory();
            }
        });
    }

    private List<Subsystem> getSubsystemsInRepository(String repoName, File gitRepoPath, AospMergeCommit mergeCommit) {
        Collection<String> manifestsInAospOld = new HashSet<>();
        Collection<String> allFilesAospOld = new HashSet<>();
        Collection<String> manifestsInAospNew = new HashSet<>();
        Collection<String> allFilesAospNew = new HashSet<>();
        Collection<String> manifestsInCm = new HashSet<>();
        Collection<String> allFilesCm = new HashSet<>();

        gitResetToCommit(gitRepoPath.getAbsolutePath(), mergeCommit.commonAncestorCommit);
        getAndroidManifestFiles(gitRepoPath, manifestsInAospOld, allFilesAospOld);

        gitResetToCommit(gitRepoPath.getAbsolutePath(), mergeCommit.aospParentCommitHash);
        getAndroidManifestFiles(gitRepoPath, manifestsInAospNew, allFilesAospNew);

        gitResetToCommit(gitRepoPath.getAbsolutePath(), mergeCommit.cmParentCommitHash);
        getAndroidManifestFiles(gitRepoPath, manifestsInCm, allFilesCm);

        // Return the intersection of the three, omit those with test and example
        List<Subsystem> result = new ArrayList<>();
        for (String aospOldManifest : manifestsInAospOld) {
            if (manifestsInAospNew.contains(aospOldManifest) && manifestsInCm.contains(aospOldManifest)) {
                File manifestFile = new File(gitRepoPath, aospOldManifest);
                String subsystemName = manifestFile.getParentFile().getName();
                if (manifestFile.getParentFile().equals(gitRepoPath)) subsystemName = "src";
                String subsystemRelativePath = manifestFile.getParentFile().getAbsolutePath().substring(gitRepoPath.getAbsolutePath().length());

                if (subsystemRelativePath.contains("/test") ||
                        subsystemRelativePath.contains("Test") ||
                        subsystemRelativePath.toLowerCase().contains("example")) {
                    continue;
                }

                // Look for src folder
                subsystemRelativePath += "/src";
                if (!allFilesAospOld.contains(subsystemRelativePath) || !allFilesAospNew.contains(subsystemRelativePath)
                        || !allFilesCm.contains(subsystemRelativePath)) continue;

                result.add(new Subsystem(repoName, subsystemName, subsystemRelativePath, gitRepoPath.getAbsolutePath()));
            }
        }
        return result;
    }

    private void checkoutRepository(Repository repository, File cloneRepoPath) {
        gitClone(repository.cmRepositoryURL, cloneRepoPath.getParentFile().getAbsolutePath(), cloneRepoPath.getName());
    }

    private File[] getProjectInputCsvFiles(File projectInputCsvPath) {
        return projectInputCsvPath.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.toLowerCase().endsWith(".csv");
            }
        });
    }

    private void getAndroidManifestFiles(File path, Collection<String> manifestFiles, Collection<String> allFiles) {

        try {
            Files.walk(Paths.get(path.getAbsolutePath()))
                    .forEach(t -> {
                        allFiles.add(t.toString().substring(path.getAbsolutePath().length()));
                        if (t.endsWith("AndroidManifest.xml"))
                            manifestFiles.add(t.toString().substring(path.getAbsolutePath().length()));
                    });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void removeFolder(File file) {
        try {
            FileUtils.deleteDirectory(file);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private void copyFolder(String srcPath, String dest) {
        File dirSrc = new File(srcPath);
        File dirDest = new File(dest);
        try {
            FileUtils.copyDirectory(dirSrc, dirDest);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private void gitClone(String url, String path, String folderName) {
        runSystemCommand(path, false, "git", "clone", url, folderName);
    }

    private void gitCreateOrChangeToPlayground(String path) {
        runSystemCommand(path, false, "git", "reset", "--hard");
        if (!gitChangeBranch(path, "playground"))
            runSystemCommand(path, false, "git", "checkout", "-b", "playground");
    }

    private boolean gitChangeBranch(String path, String branchName) {
        String result = runSystemCommand(path, false, "git", "checkout", branchName);
        return !result.toLowerCase().contains("did not match any");
    }

    private boolean gitResetToCommit(String path, String commitHash) {
        gitCreateOrChangeToPlayground(path);
        String result = runSystemCommand(path, false, "git", "reset", "--hard", commitHash);
        return !result.toLowerCase().contains("ambiguous argument");
    }

    private boolean gitReplayAospMerge(String path, AospMergeCommit mergeCommit) {
        if (!gitResetToCommit(path, mergeCommit.cmParentCommitHash)) return false;
        String result = runSystemCommand(path, false, "git", "merge", mergeCommit.aospParentCommitHash);
        if (result.toLowerCase().contains("Automatic merge failed".toLowerCase())) {
            gitResetToCommit(path, mergeCommit.cmParentCommitHash);
            return false;
        }
        return true;
    }

    private List<AospMergeCommit> findUpstreamMergeCommits(String path) {
        List<AospMergeCommit> mergeCommits = new ArrayList<>();
        String currentCommit = runSystemCommand(path, false, "git", "log", "-1", "--format=%H").trim();
        for (int i = 0; i < 200; i++) {
            AospMergeCommit mergeCommit = populateMergeCommit(path, currentCommit);
            if (mergeCommit != null && mergeCommit.cmParentCommitHash != null) {
                if (mergeCommit.aospParentCommitHash != null && mergeCommit.commonAncestorCommit != null) {

                    mergeCommits.add(mergeCommit);
                }
                currentCommit = mergeCommit.cmParentCommitHash;
            } else {
                break;
            }
        }
        Collections.reverse(mergeCommits);
        return mergeCommits;
    }

    private AospMergeCommit populateMergeCommit(String path, String mergeCommitHash) {
        String parentCommits = runSystemCommand(path, false, "git", "log", "-1", "--format=%P", mergeCommitHash).trim();
        if (parentCommits.trim().equals("")) return null;

        // This is a merge commit
        if (parentCommits.contains(" ")) {
            String commitMessage = runSystemCommand(path, false, "git", "log", "-1", "--format=%B", mergeCommitHash);
            String cmParentCommit = parentCommits.split(" ")[0];
            String aospParentCommit = parentCommits.split(" ")[1];
            String commonAncestorCommit = runSystemCommand(path, false, "git", "merge-base",
                    cmParentCommit, aospParentCommit).trim();
            if (commitMessage.matches(".*(android-\\d+.+_r\\d+)[\\S\\s]*")) {
                return new AospMergeCommit(mergeCommitHash, cmParentCommit, aospParentCommit,
                        commonAncestorCommit,
                        commitMessage.replaceFirst(".*(android-\\d+.+_r\\d+)[\\S\\s]*", "$1"));
            }
            return new AospMergeCommit(mergeCommitHash, cmParentCommit, aospParentCommit,
                    commonAncestorCommit, null);
        }
        return new AospMergeCommit(mergeCommitHash, parentCommits, null,
                null, null);
    }

    private class AospMergeCommit {
        String commitHash, cmParentCommitHash, aospParentCommitHash, commonAncestorCommit, aospTagName;

        public AospMergeCommit(String commitHash, String cmParentCommitHash, String aospParentCommitHash, String commonAncestorCommit, String aospTagName) {
            this.commitHash = commitHash;
            this.cmParentCommitHash = cmParentCommitHash;
            this.aospParentCommitHash = aospParentCommitHash;
            this.commonAncestorCommit = commonAncestorCommit;
            this.aospTagName = aospTagName;
        }
    }

    private class Subsystem {
        String repository;
        String name;
        String relativePath;
        String gitRepoPath;

        public Subsystem(String repository, String name, String relativePath, String gitRepoPath) {
            this.repository = repository;
            this.name = name;
            this.relativePath = relativePath;
            this.gitRepoPath = gitRepoPath;
        }

        @Override
        public String toString() {
            return name + " (" + relativePath + ")";
        }
    }

    private class ComparisionFolder {
        String oldVersionName;
        String newVersionName;
        String rootPath;

        ComparisionFolder(String rootPath, String oldVersionName, String newVersionName) {
            this.oldVersionName = oldVersionName;
            this.newVersionName = newVersionName;
            this.rootPath = rootPath;
            File folder = new File(getPath());
            if (!folder.exists()) {
                folder.mkdir();
            }
            File oldVersionFolder = new File(getOldVersionPath());
            if (!oldVersionFolder.exists()) {
                oldVersionFolder.mkdir();
            }
            File newVersionFolder = new File(getNewVersionPath());
            if (!newVersionFolder.exists()) {
                newVersionFolder.mkdir();
            }
        }


        public String getName() {
            return oldVersionName + "-" + newVersionName;
        }

        public String getPath() {
            return new File(rootPath, getName()).getAbsolutePath();
        }

        String getOldVersionPath() {
            return new File(getPath(), "old").getAbsolutePath();
        }

        String getNewVersionPath() {
            return new File(getPath(), "new").getAbsolutePath();
        }
    }

    private class Repository {
        String name;
        String cmRepositoryURL;

        Repository(String name, String cmRepositoryURL) {
            this.name = name;
            this.cmRepositoryURL = cmRepositoryURL;
        }

        @Override
        public String toString() {
            return name;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof Repository)) return false;
            return ((Repository) obj).name.equals(name);
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }
    }

}
