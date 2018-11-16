#!/bin/bash

# NEVER RUN THIS TEST CASE ON AN ENVIRONMENT WHERE THE DATA MATTERS!
# This test case assumes you have built the batch_import jar file with a relevant secret.properties baked in.
# It also assumes that 'jq' is installed on the system.
# It also assumes it is ok to delete all data with changedIn = 'importtest' as a method for cleaning up!

function cleanup {
    psql whelk_dev <<< "delete from lddb__identifiers where id in (select id from lddb where changedIn = 'importtest');"
    psql whelk_dev <<< "delete from lddb__dependencies where id in (select id from lddb where changedIn = 'importtest');"
    psql whelk_dev <<< "delete from lddb where changedIn = 'importtest';"
}

OUTCOME=$"Failures if any: "


function fail {
    OUTCOME=$"${OUTCOME}[Failed: $1], "
}

# Start with clean slate
cleanup

pushd ../


######## NORMAL IMPORT
# Check created bib and attached hold
java -jar build/libs/batchimport.jar --path=./integtest/batch0.xml --format=xml --dupType=ISBNA,ISBNZ,ISSNA,ISSNZ,035A --live --changedIn=importtest
bibResourceId=$(psql -qAt whelk_dev <<< "select data from lddb where changedIn = 'importtest' and collection = 'bib'" | jq '.["@graph"]|.[1]|.["@id"]')
itemOf=$(psql -qAt whelk_dev <<< "select data from lddb where changedIn = 'importtest' and collection = 'hold'" | jq '.["@graph"]|.[1].itemOf|.["@id"]')
if [ "$itemOf" != "$bibResourceId" ]; then
    fail "Normal import"
fi

######## RERUN SAME FILE, NO MORE DATA
# Re-run same file, should result in no changes
java -jar build/libs/batchimport.jar --path=./integtest/batch0.xml --format=xml --dupType=ISBNA,ISBNZ,ISSNA,ISSNZ,035A --live --changedIn=importtest
rowCount=$(psql -qAt whelk_dev <<< "select count(*) from lddb where changedIn = 'importtest' and collection = 'bib'")
if (( $rowCount != 1 )) ; then
    fail "Expected single bib record"
fi
rowCount=$(psql -qAt whelk_dev <<< "select count(*) from lddb where changedIn = 'importtest' and collection = 'hold'")
if (( $rowCount != 1 )) ; then
    fail "Expected single hold record"
fi

######## REPLACE RECORD. batch1.xml contains the same data but with a changed title for the bib record and another 035$a
java -jar build/libs/batchimport.jar --path=./integtest/batch1.xml --format=xml --dupType=ISBNA,ISBNZ,ISSNA,ISSNZ,035A --live --changedIn=importtest --replaceBib
newBibResourceId=$(psql -qAt whelk_dev <<< "select data from lddb where changedIn = 'importtest' and collection = 'bib'" | jq '.["@graph"]|.[1]|.["@id"]')
if [ $newBibResourceId != $bibResourceId ]; then
    fail "Bib-replace altered the ID!"
fi
mainTitle=$(psql -qAt whelk_dev <<< "select data from lddb where changedIn = 'importtest' and collection = 'bib'" | jq '.["@graph"]|.[1]|.["hasTitle"]|.[0]|.["mainTitle"]')
expect="\"Polisbilen får INTE ett larm\""
if [ "$mainTitle" != "$expect" ]; then
    fail "Data was not replaced!"
fi
systemNumbers=$(psql -qAt whelk_dev <<< "select data from lddb where changedIn = 'importtest' and collection = 'bib'" | jq '.["@graph"]|.[0]|.["identifiedBy"]|.[]|.["value"]')
if [[ "$systemNumbers" != *"NEW"* ]]; then
    fail "Systemnumber was not added!"
fi
if [[ "$systemNumbers" != *"OLD"* ]]; then
    fail "Systemnumber was not retained!"
fi

cleanup
######## ISBN 10/13 matching. Batch 2 and 3 contain the same data but with varying forms of the same ISBN.
java -jar build/libs/batchimport.jar --path=./integtest/batch2.xml --format=xml --live --changedIn=importtest
java -jar build/libs/batchimport.jar --path=./integtest/batch3.xml --format=xml --dupType=ISBNA --live --changedIn=importtest
rowCount=$(psql -qAt whelk_dev <<< "select count(*) from lddb where changedIn = 'importtest' and collection = 'bib'")
if (( $rowCount != 1 )) ; then
    fail "Expected single bib record (after IBSN10/13 test)"
fi
rowCount=$(psql -qAt whelk_dev <<< "select count(*) from lddb where changedIn = 'importtest' and collection = 'hold'")
if (( $rowCount != 1 )) ; then
    fail "Expected single hold record (after IBSN10/13 test)"
fi

cleanup
######## ISBN $z (hidden value) matching. Batch 4 contain the same data but with its ISBN in 020$z
java -jar build/libs/batchimport.jar --path=./integtest/batch4.xml --format=xml --live --changedIn=importtest
java -jar build/libs/batchimport.jar --path=./integtest/batch4.xml --format=xml --dupType=ISBNZ --live --changedIn=importtest
rowCount=$(psql -qAt whelk_dev <<< "select count(*) from lddb where changedIn = 'importtest' and collection = 'bib'")
if (( $rowCount != 1 )) ; then
    fail "Expected single bib record (after IBSN hidden value test)"
fi
rowCount=$(psql -qAt whelk_dev <<< "select count(*) from lddb where changedIn = 'importtest' and collection = 'hold'")
if (( $rowCount != 1 )) ; then
    fail "Expected single hold record (after IBSN hidden value test)"
fi

cleanup
######## ISSN $z (hidden value) matching. Batch 4 contain the same data but with its ISSN in 022$z
java -jar build/libs/batchimport.jar --path=./integtest/batch5.xml --format=xml --live --changedIn=importtest
java -jar build/libs/batchimport.jar --path=./integtest/batch5.xml --format=xml --dupType=ISSNZ --live --changedIn=importtest
rowCount=$(psql -qAt whelk_dev <<< "select count(*) from lddb where changedIn = 'importtest' and collection = 'bib'")
if (( $rowCount != 1 )) ; then
    fail "Expected single bib record (after ISSN hidden value test)"
fi
rowCount=$(psql -qAt whelk_dev <<< "select count(*) from lddb where changedIn = 'importtest' and collection = 'hold'")
if (( $rowCount != 1 )) ; then
    fail "Expected single hold record (after ISSN hidden value test)"
fi

cleanup
######## Introduce multiple duplicates (without checking) and make sure incoming records afterwards still place their
# holdings on at least one of the detected duplicates
java -jar build/libs/batchimport.jar --path=./integtest/batch0.xml --format=xml --live --changedIn=importtest
java -jar build/libs/batchimport.jar --path=./integtest/batch0.xml --format=xml --live --changedIn=importtest
java -jar build/libs/batchimport.jar --path=./integtest/batch1.xml --format=xml --dupType=ISBNA,ISBNZ,ISSNA,ISSNZ,035A --live --changedIn=importtest
rowCount=$(psql -qAt whelk_dev <<< "select count(*) from lddb where changedIn = 'importtest' and collection = 'bib'")
if (( $rowCount != 2 )) ; then
    fail "Expected exactly 2 bib records"
fi
rowCount=$(psql -qAt whelk_dev <<< "select count(*) from lddb where changedIn = 'importtest' and collection = 'hold'")
if (( $rowCount != 3 )) ; then
    fail "Expected exactly 3 hold records"
fi

cleanup
######## Electronic/Instance should not match. Batch 6 contains the same record, but work type is Multimedia instead of Text
java -jar build/libs/batchimport.jar --path=./integtest/batch0.xml --format=xml --live --changedIn=importtest
java -jar build/libs/batchimport.jar --path=./integtest/batch6.xml --format=xml --dupType=ISBNA --live --changedIn=importtest
rowCount=$(psql -qAt whelk_dev <<< "select count(*) from lddb where changedIn = 'importtest' and collection = 'bib'")
if (( $rowCount != 2 )) ; then
    fail "Expected 2 bib records after importing Instance and Electronic"
fi
rowCount=$(psql -qAt whelk_dev <<< "select count(*) from lddb where changedIn = 'importtest' and collection = 'hold'")
if (( $rowCount != 2 )) ; then
    fail "Expected 2 bib records after importing Instance and Electronic"
fi

cleanup
######## Electronic/Instance should not match. Batch 7 contains the same record, but instance type is electronic
java -jar build/libs/batchimport.jar --path=./integtest/batch0.xml --format=xml --live --changedIn=importtest
java -jar build/libs/batchimport.jar --path=./integtest/batch7.xml --format=xml --dupType=ISBNA --live --changedIn=importtest
rowCount=$(psql -qAt whelk_dev <<< "select count(*) from lddb where changedIn = 'importtest' and collection = 'bib'")
if (( $rowCount != 2 )) ; then
    fail "Expected 2 bib records after importing Instance and Electronic"
fi
rowCount=$(psql -qAt whelk_dev <<< "select count(*) from lddb where changedIn = 'importtest' and collection = 'hold'")
if (( $rowCount != 2 )) ; then
    fail "Expected 2 bib records after importing Instance and Electronic"
fi

cleanup
######## Electronic/Instance should not match. Batch 9 contains the same record, but instance type is electronic
java -jar build/libs/batchimport.jar --path=./integtest/batch0.xml --format=xml --live --changedIn=importtest
java -jar build/libs/batchimport.jar --path=./integtest/batch9.xml --format=xml --dupType=ISBNA --live --changedIn=importtest
rowCount=$(psql -qAt whelk_dev <<< "select count(*) from lddb where changedIn = 'importtest' and collection = 'bib'")
if (( $rowCount != 2 )) ; then
    fail "Expected 2 bib records after importing Instance and Electronic"
fi
rowCount=$(psql -qAt whelk_dev <<< "select count(*) from lddb where changedIn = 'importtest' and collection = 'hold'")
if (( $rowCount != 2 )) ; then
    fail "Expected 2 bib records after importing Instance and Electronic"
fi

cleanup
######## ISBN $z 10/13 matching. Batch 4 and 8 contain the same data but with varying forms of the same ISBN in 020$z.
java -jar build/libs/batchimport.jar --path=./integtest/batch4.xml --format=xml --live --changedIn=importtest
java -jar build/libs/batchimport.jar --path=./integtest/batch8.xml --format=xml --dupType=ISBNZ --live --changedIn=importtest
rowCount=$(psql -qAt whelk_dev <<< "select count(*) from lddb where changedIn = 'importtest' and collection = 'bib'")
if (( $rowCount != 1 )) ; then
    fail "Expected single bib record (after IBSN$z 10/13 test)"
fi
rowCount=$(psql -qAt whelk_dev <<< "select count(*) from lddb where changedIn = 'importtest' and collection = 'hold'")
if (( $rowCount != 1 )) ; then
    fail "Expected single hold record (after IBSN$z 10/13 test)"
fi

cleanup
# Should match an existing example record (on Voyager-style LIBRIS-ID), and only add the holding
java -jar build/libs/batchimport.jar --path=./integtest/batch10.xml --format=xml --dupType=LIBRIS-ID --live --changedIn=importtest
rowCount=$(psql -qAt whelk_dev <<< "select count(*) from lddb where changedIn = 'importtest' and collection = 'bib'")
if (( $rowCount != 0 )) ; then
    fail "Expected no bib record"
fi
rowCount=$(psql -qAt whelk_dev <<< "select count(*) from lddb where changedIn = 'importtest' and collection = 'hold'")
if (( $rowCount != 1 )) ; then
    fail "Expected single hold record"
fi

cleanup
# Test EAN match
java -jar build/libs/batchimport.jar --path=./integtest/batch11.xml --format=xml --live --changedIn=importtest
java -jar build/libs/batchimport.jar --path=./integtest/batch11.xml --format=xml --dupType=EAN --live --changedIn=importtest
rowCount=$(psql -qAt whelk_dev <<< "select count(*) from lddb where changedIn = 'importtest' and collection = 'bib'")
if (( $rowCount != 1 )) ; then
    fail "Expected single bib record (after EAN test)"
fi
rowCount=$(psql -qAt whelk_dev <<< "select count(*) from lddb where changedIn = 'importtest' and collection = 'hold'")
if (( $rowCount != 1 )) ; then
    fail "Expected single hold record (after EAN test)"
fi

cleanup
popd
echo $OUTCOME
