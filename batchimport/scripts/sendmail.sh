#!/bin/sh
 
HOME=`dirname $0`/..
#MAILTO="anders.cato@kb.se martin.malmsten@kb.se christer.larsson@libris.kb.se"
MAILTO="martin.malmsten@kb.se"
ECHO=/usr/bin/echo
#ECHO="/bin/echo -e"
MAIL="mail"

if [ $# -ne 2 ] ; then
  echo "usage: $0 <queuename> <logfile>"
  exit 1
fi

QUEUE=$1
LOGFILE=$2

N_ADDED=`grep -c "add-bib:" $LOGFILE`
N_ADDMFHD=`grep -c "add-hol:" $LOGFILE`
#N_SKIPPED=`grep -c "skip:" $LOGFILE`
N_ADDMFHD=`expr $N_ADDMFHD - $N_ADDED`
cut -b 10- $LOGFILE | sort | uniq > /tmp/import.$$.tmp
 
$ECHO "From: metadatatratten@libris.kb.se\nreply-to: martin.malmsten@kb.se\nsubject: Metadatatratten v2 ($QUEUE) `date +"%Y%m%d-%H:%M:%S"`\nContent-Type: text/plain; CHARSET=iso-8859-1\nDetta �r ett automagiskt meddelande fr�n importrutinen f�r $QUEUE.\n\nResultat:\n  $N_ADDED bibliografiska post(er) med tillh�rande best�ndspost har lagts till.\n  $N_ADDMFHD best�ndspost(er) har lagts till redan befintlig bibliografisk post.\n\nUtf�rlig log f�ljer:\n\nBIB_ID         ISXN              TITEL                         UPPHOV\n-------------------------------------------------------------------------------`cat /tmp/import.$$.tmp`\n-------------------------------------------------------------------------------" | $MAIL $MAILTO

rm /tmp/import.$$.tmp
