/* Compact UMKD disclosures move original nodes and restore their exact positions. */
(function () {
    'use strict';
    if (location.origin !== 'https://univer.kaznu.kz' || window.__univerMaterialsV1) return;
    var records = new Map();
    // File kinds the badge tells apart at a glance; anything else keeps its own short extension.
    var kinds = {pdf:'pdf', doc:'doc', docx:'doc', rtf:'doc', odt:'doc', xls:'xls', xlsx:'xls', csv:'xls', ods:'xls', ppt:'ppt', pptx:'ppt', odp:'ppt',
        zip:'zip', rar:'zip', '7z':'zip', gz:'zip', tar:'zip', png:'img', jpg:'img', jpeg:'img', gif:'img', bmp:'img', svg:'img',
        mp4:'media', avi:'media', mov:'media', mkv:'media', mp3:'media', wav:'media', txt:'txt'};
    function restore(record) {
        record.moves.slice().reverse().forEach(function (move) { if (move.home.parentNode) move.home.replaceWith(move.node); });
        record.box.remove();
        delete record.row.dataset.univerExt; delete record.row.dataset.univerExtLabel;
    }
    function extension(row) {
        var name = (row.querySelector('a.downLoad')?.textContent || '').trim();
        var match = name.match(/\.([a-z0-9]{1,5})$/i);
        return match ? match[1].toLowerCase() : '';
    }
    function apply(row, language) {
        records.forEach(function (record, source) {
            if (!source.isConnected || !record.box.isConnected) { restore(record); records.delete(source); }
        });
        if (row.cells.length < 7) return;
        if (!records.has(row)) {
            var box = document.createElement('details'); box.className = 'univer-app-material-details';
            var title = document.createElement('summary'); box.appendChild(title);
            var moves = [];
            // The date stays beside language and size; only the description and the rating fold away.
            [2,6].forEach(function (index) {
                var cell = row.cells[index], content = document.createElement('div');
                if (cell.dataset.univerField) {
                    var label = document.createElement('strong'); label.textContent = cell.dataset.univerField + ': '; content.appendChild(label);
                }
                Array.from(cell.childNodes).forEach(function (node) {
                    var home = document.createComment('univer-material-home'); node.before(home); content.appendChild(node); moves.push({node:node,home:home});
                }); box.appendChild(content);
            });
            row.cells[6].appendChild(box); records.set(row,{box:box,title:title,moves:moves,row:row,hasDescription:!!box.children[1]?.textContent.replace(/^[^:]*:\s*/, '').trim()});
        }
        var record = records.get(row), ext = extension(row), kind = kinds[ext] || 'file';
        // Without a description the fold is only about rating the material.
        record.title.textContent = record.hasDescription
            ? (language==='en'?'Details and rating':language==='kz'?'Сипаттама және баға':'Описание и оценка')
            : (language==='en'?'Rate this material':language==='kz'?'Материалды бағалау':'Оценить материал');
        if (row.dataset.univerExt !== kind) row.dataset.univerExt = kind;
        var badge = (ext && ext.length <= 4 ? ext : 'FILE').toUpperCase();
        if (row.dataset.univerExtLabel !== badge) row.dataset.univerExtLabel = badge;
        row.querySelector('a.downLoad')?.setAttribute('data-univer-action',language==='en'?'Download':language==='kz'?'Жүктеу':'Скачать');
    }
    function release() { records.forEach(restore); records.clear(); }
    window.__univerMaterialsV1 = {apply:apply,release:release};
})();
