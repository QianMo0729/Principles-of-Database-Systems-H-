package dbbench;

import dbbench.data.PrepareData;
import dbbench.db.DbAdmin;
import dbbench.db.DbLoadTest;
import dbbench.db.DbReliability;
import dbbench.db.DbSearch;
import dbbench.db.DbSetup;
import dbbench.db.DbUpdate;
import dbbench.file.FileCounter;
import dbbench.file.FileSearch;
import dbbench.file.FileUpdate;
import dbbench.file.MemIndex;
import dbbench.util.Args;

/**
 * Entry point: {@code java dbbench.Main <command> --option value ...}.
 *
 * <p>Each command runs one measurement and appends its results to the CSV given by --out. The
 * scripts in Codes/scripts decide which commands run, in which order and on which VM spec.
 */
public final class Main {
    private Main() {}

    public static void main(String[] argv) throws Exception {
        if (argv.length == 0) {
            System.err.println("usage: dbbench.Main <command> [--option value ...]");
            System.exit(2);
        }
        Args a = new Args(argv, 1);
        switch (argv[0]) {
            case "prep" -> PrepareData.run(a);
            // data operations in files
            case "file-search" -> FileSearch.run(a);
            case "file-index" -> MemIndex.run(a);
            case "file-update" -> FileUpdate.run(a);
            case "file-check" -> FileUpdate.check(a);
            case "file-counter" -> FileCounter.run(a);
            // the same operations through a DBMS
            case "db-wait" -> DbAdmin.waitReady(a);
            case "db-info" -> DbAdmin.info(a);
            case "db-setup" -> DbSetup.setup(a);
            case "db-index" -> DbSetup.index(a);
            case "db-search" -> DbSearch.run(a);
            case "db-update" -> DbUpdate.run(a);
            case "db-load" -> DbLoadTest.run(a);
            case "db-heavy" -> DbAdmin.heavy(a);
            // reliability
            case "db-lostupdate" -> DbReliability.lostUpdate(a);
            case "db-uniquerace" -> DbReliability.uniqueRace(a);
            case "db-errors" -> DbReliability.errors(a);
            case "db-connlimit" -> DbReliability.connLimit(a);
            case "db-ledger-run" -> DbReliability.ledgerRun(a);
            case "db-ledger-verify" -> DbReliability.ledgerVerify(a);
            case "db-bulk-start" -> DbReliability.bulkStart(a);
            case "db-bulk-verify" -> DbReliability.bulkVerify(a);
            default -> {
                System.err.println("unknown command: " + argv[0]);
                System.exit(2);
            }
        }
    }
}
