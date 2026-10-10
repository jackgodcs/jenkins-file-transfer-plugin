(function () {
  'use strict';

  const root = document.head.dataset.rooturl || '';
  const chunkSize = 8 * 1024 * 1024;

  function jobName() {
    const path = window.location.pathname.replace(/\/build(?:WithParameters)?\/?$/, '');
    const parts = path.split('/').filter(Boolean);
    const names = [];
    for (let i = 0; i < parts.length; i += 1) if (parts[i] === 'job' && parts[i + 1]) names.push(decodeURIComponent(parts[++i]));
    return names.join('/');
  }

  function request(url, options, retries) {
    return fetch(root + url, Object.assign({credentials: 'same-origin'}, options)).then(function (response) {
      if (!response.ok) throw new Error(response.status + ' ' + (response.statusText || 'Upload request failed'));
      return response.headers.get('content-type') && response.headers.get('content-type').indexOf('json') >= 0 ? response.json() : {};
    }).catch(function (error) {
      if (retries > 0) return new Promise(function (resolve) { setTimeout(resolve, 500); }).then(function () { return request(url, options, retries - 1); });
      throw error;
    });
  }

  function initialize(control) {
    if (control.dataset.initialized) return;
    control.dataset.initialized = 'true';
    const fileInput = control.querySelector('.file-transfer-file');
    const folderInput = control.querySelector('.file-transfer-folder');
    const tokenInput = control.querySelector('.file-transfer-token');
    const summary = control.querySelector('.file-transfer-summary');
    const filesPreview = control.querySelector('.file-transfer-files');
    const progress = control.querySelector('.file-transfer-progress');
    const progressText = control.querySelector('.file-transfer-progress-text');
    const cancel = control.querySelector('.file-transfer-cancel');
    let cancelled = false;

    control.querySelector('.file-transfer-file-button').onclick = function () { fileInput.click(); };
    control.querySelector('.file-transfer-folder-button').onclick = function () { folderInput.click(); };
    cancel.onclick = function () { cancelled = true; tokenInput.defaultValue = ''; tokenInput.value = ''; summary.textContent = control.dataset.messageCancelled; progress.hidden = true; progressText.hidden = true; cancel.hidden = true; };
    fileInput.onchange = function () { if (fileInput.files.length) upload([fileInput.files[0]], false); };
    folderInput.onchange = function () { if (folderInput.files.length) upload(Array.from(folderInput.files), true); };

    async function upload(files, folder) {
      cancelled = false; tokenInput.defaultValue = ''; tokenInput.value = '';
      const bytes = files.reduce(function (sum, file) { return sum + file.size; }, 0);
      if (files.length > Number(control.dataset.maxFiles) || bytes > Number(control.dataset.maxBytes)) { summary.textContent = control.dataset.messageLimit; return; }
      const top = folder ? files[0].webkitRelativePath.split('/')[0] : files[0].name;
      filesPreview.replaceChildren();
      files.forEach(function (file) {
        const item = document.createElement('li');
        item.textContent = (folder ? file.webkitRelativePath : file.name) + ' — ' + file.size + ' ' + control.dataset.messageBytes;
        filesPreview.appendChild(item);
      });
      filesPreview.hidden = false; progress.hidden = false; progress.value = 0; progressText.hidden = false; progressText.textContent = '0%'; cancel.hidden = false; summary.textContent = control.dataset.messageSummary.replace('{0}', top).replace('{1}', files.length).replace('{2}', bytes);
      try {
        const query = new URLSearchParams({job: jobName(), parameter: control.dataset.parameter, name: top, folder: String(folder), bytes: String(bytes), files: String(files.length)});
        const session = await request('/file-transfer/create?' + query, {method: 'POST', headers: crumbHeaders()}, 0);
        let completed = 0;
        for (const file of files) {
          if (cancelled) throw new Error(control.dataset.messageCancelled);
          const path = folder ? file.webkitRelativePath : file.name;
          if (file.size === 0) {
            const emptyQuery = new URLSearchParams({token: session.token, path: path, size: '0', offset: '0', chunkBytes: '0', last: 'true', job: jobName(), parameter: control.dataset.parameter});
            await request('/file-transfer/file?' + emptyQuery, {method: 'POST', headers: crumbHeaders({'Content-Type': 'application/octet-stream'}), body: new Blob([])}, 3);
          } else {
            for (let offset = 0; offset < file.size; offset += chunkSize) {
              if (cancelled) throw new Error(control.dataset.messageCancelled);
              const end = Math.min(offset + chunkSize, file.size);
              const fileQuery = new URLSearchParams({token: session.token, path: path, size: String(file.size), offset: String(offset), chunkBytes: String(end - offset), last: String(end === file.size), job: jobName(), parameter: control.dataset.parameter});
              await request('/file-transfer/file?' + fileQuery, {method: 'POST', headers: crumbHeaders({'Content-Type': 'application/octet-stream'}), body: file.slice(offset, end)}, 3);
              progress.value = bytes ? Math.round((completed + end) * 100 / bytes) : 100;
              progressText.textContent = progress.value + '% — ' + path;
            }
          }
          completed += file.size;
        }
        await request('/file-transfer/complete?' + new URLSearchParams({token: session.token, job: jobName(), parameter: control.dataset.parameter}), {method: 'POST', headers: crumbHeaders()}, 1);
        tokenInput.defaultValue = session.token; tokenInput.value = session.token; progress.value = 100; progressText.textContent = '100%'; cancel.hidden = true; summary.textContent = top + ' — ' + control.dataset.messageComplete;
      } catch (error) { tokenInput.defaultValue = ''; tokenInput.value = ''; progress.hidden = true; progressText.hidden = true; cancel.hidden = true; summary.textContent = control.dataset.messageFailed + ' (' + error.message + ')'; }
    }
  }

  function crumbHeaders(extra) {
    const headers = Object.assign({}, extra || {});
    if (window.crumb && window.crumb.fieldName) headers[window.crumb.fieldName] = window.crumb.value;
    return headers;
  }

  function scan() { document.querySelectorAll('.file-transfer-upload').forEach(initialize); }
  document.addEventListener('DOMContentLoaded', scan);
  new MutationObserver(scan).observe(document.documentElement, {childList: true, subtree: true});
}());
