package kz.kaznaru.fim.model;

/** Result of comparing a file with its trusted baseline. */
public enum FileStatus {
    SAFE, MODIFIED, NEW, DELETED, ERROR
}
