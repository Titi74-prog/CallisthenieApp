/** @OnlyCurrentDoc */
/* Milestones → Google Sheets : reçoit les pesées Xiaomi S400 envoyées par l'appli.
   À coller dans la feuille « Milestones — Pesées » : Extensions → Apps Script.
   @OnlyCurrentDoc : le script n'a accès qu'à cette feuille, à aucune autre de ton Drive.
   Pour changer de clé : modifie JETON, redéploie (Gérer les déploiements → Modifier → Nouvelle version),
   puis colle la nouvelle adresse ?k=… dans l'appli (carte Balance S400 → régler). */
const JETON = 'A_REMPLACER_PAR_TA_CLE_SECRETE';  // ne jamais pousser la vraie clé sur GitHub

function doPost(e) {
  if (!e || !e.parameter || e.parameter.k !== JETON) return repondre({ ok: false, err: 'jeton' });
  const data = JSON.parse(e.postData.contents);
  const lignes = data.rows || [data];
  const sh = SpreadsheetApp.getActiveSpreadsheet().getSheets()[0];
  const verrou = LockService.getScriptLock();
  verrou.waitLock(20000);
  try {
    sh.getRange('A:A').setNumberFormat('@');          // dates gardées en texte AAAA-MM-JJ
    const entetes = sh.getRange(1, 1, 1, sh.getLastColumn()).getValues()[0];
    const n = sh.getLastRow();
    const dates = n > 1 ? sh.getRange(2, 1, n - 1, 1).getDisplayValues().map(r => r[0]) : [];
    let ecrites = 0;
    lignes.forEach(r => {
      if (!r || !/^\d{4}-\d{2}-\d{2}$/.test(r.date)) return;
      const v = r.v || {};
      const ligne = entetes.map(h => h === 'date' ? r.date : h === 'recu' ? new Date() : (v[h] != null ? v[h] : ''));
      const i = dates.indexOf(r.date);
      if (i >= 0) sh.getRange(i + 2, 1, 1, ligne.length).setValues([ligne]);
      else { sh.appendRow(ligne); dates.push(r.date); }
      ecrites++;
    });
    if (sh.getLastRow() > 2) sh.getRange(2, 1, sh.getLastRow() - 1, sh.getLastColumn()).sort(1);
    return repondre({ ok: true, n: ecrites });
  } finally {
    verrou.releaseLock();
  }
}

function doGet(e) {
  return repondre({ ok: !!(e && e.parameter && e.parameter.k === JETON), service: 'Milestones pesées' });
}

function repondre(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}
