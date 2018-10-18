// Assuming the full list in: länkserver-borttagning_MASTER.tsv (but with column names removed)
// the holdids are retrieved as such:
// cat länkserver-borttagning_MASTER.tsv | cut -f2 > hold-IDs
// the bibids are retrieved as such:
// cat länkserver-borttagning_MASTER.tsv | cut -f1 | sort -u > bib-IDs

PrintWriter failedIDs = new PrintWriter(new File ("failed-to-delete-IDs"))
try {

    File holdIDsFile = new File(scriptDir, "linkserv-holdIDs")
    selectByIds( holdIDsFile.readLines() ) {
        try {
            it.scheduleDelete() // Will this really throw in the correct place with batching?
        } catch (Throwable e) {
            failedIDs.print("Failed to delete ${it.doc.getShortId()} due to: $e")
        }
    }

    File bibIDsFile = new File(scriptDir, "linkserv-bibIDs")
    selectByIds( bibIDsFile.readLines() ) {
        try {
            it.scheduleDelete() // Will this really throw in the correct place with batching?
        } catch (Throwable e) {
            failedIDs.print("Failed to delete ${it.doc.getShortId()} due to: $e")
        }
    }

}
finally {
    failedIDs.close()
}