(() => {
  const $ = (selector, root = document) => root.querySelector(selector);
  const csrf = () => $('meta[name="csrf-token"]').content;
  const state = { csrf: csrf(), configured: false, authenticated: false, tokenRequired: document.body.dataset.tokenRequired === 'true', devices: [], monitoringEnabled: false, session: 0 };
  const wizard = { open: false, run: 0, step: 0, busy: false, device: null, inventory: [], saved: new Set(), pairingMode: 'new' };
  const authPanel = $('#auth-panel');
  const dashboard = $('#dashboard');
  const notice = $('#notice');
  const setNotice = (message, error = false) => { notice.textContent = message || ''; notice.style.color = error ? 'var(--red)' : 'var(--green)'; };
  const api = async (url, options = {}) => {
    const headers = new Headers(options.headers || {});
    if (options.method && options.method !== 'GET') headers.set('X-CSRF-Token', state.csrf);
    if (options.body && typeof options.body !== 'string') { headers.set('Content-Type', 'application/json'); options.body = JSON.stringify(options.body); }
    const response = await fetch(url, { ...options, headers, credentials: 'same-origin' });
    const payload = response.status === 204 ? {} : await response.json().catch(() => ({}));
    if (!response.ok) { const error = new Error(payload.detail || payload.error || 'Request failed.'); error.status = response.status; throw error; }
    return payload;
  };
  const formData = form => Object.fromEntries(new FormData(form));
  const button = (text, fn, kind = 'secondary') => { const b = document.createElement('button'); b.type = 'button'; b.className = kind; b.textContent = text; b.addEventListener('click', fn); return b; };
  const clearPairingCode = () => { $('#wizard-code').value = ''; const manual = $('#pair-form [name="pairing_code"]'); if (manual) manual.value = ''; };
  const showAuth = (configured, tokenRequired = state.tokenRequired) => {
    state.configured = configured; state.authenticated = false; state.tokenRequired = tokenRequired; state.session += 1;
    closeWizard(); clearPairingCode();
    authPanel.classList.remove('hidden'); dashboard.classList.add('hidden');
    $('#bootstrap-help').classList.toggle('hidden', configured || !tokenRequired);
    $('#bootstrap-copy-status').textContent = '';
    $('#token-label').classList.toggle('hidden', configured || !tokenRequired);
    $('#token').required = !configured && tokenRequired;
    $('#token').disabled = configured || !tokenRequired;
    $('#password').autocomplete = configured ? 'current-password' : 'new-password';
    $('#password').minLength = configured ? 1 : 12;
    $('#auth-title').textContent = configured ? 'Sign in' : 'Set up your local account';
    $('#auth-copy').textContent = configured ? 'Sign in to manage TVs and queued work on this service.' : tokenRequired ? 'Enter the one-time token and choose a strong password of at least 12 characters.' : 'Choose a password of at least 12 characters to create your local account.';
    $('#auth-submit').textContent = configured ? 'Sign in' : 'Create account';
  };
  const loadSession = async () => {
    const data = await api('/api/session');
    if (data.authenticated && data.csrf) {
      state.session += 1; const session = state.session; state.authenticated = true; state.csrf = data.csrf;
      $('#logout').classList.remove('hidden'); dashboard.classList.remove('hidden'); authPanel.classList.add('hidden');
      await refreshAll(session);
    } else showAuth(data.configured, data.token_required);
  };
  $('#auth-form').addEventListener('submit', async event => {
    event.preventDefault(); const fields = formData(event.currentTarget); fields.csrf = state.csrf;
    try {
      const login = state.configured;
      if (login || !state.tokenRequired) delete fields.token;
      const result = await api(login ? '/api/login' : '/api/setup', { method: 'POST', body: fields });
      state.csrf = result.csrf; $('meta[name="csrf-token"]').content = result.csrf;
      $('#password').value = ''; $('#token').value = '';
      await loadSession();
    }
    catch (error) { $('#auth-message').textContent = error.message; }
  });
  $('#copy-bootstrap-command').addEventListener('click', async () => {
    const command = $('#bootstrap-command');
    try {
      await navigator.clipboard.writeText(command.textContent);
      $('#bootstrap-copy-status').textContent = 'Command copied. Run it in the service container console.';
    } catch {
      const selection = window.getSelection();
      const range = document.createRange();
      range.selectNodeContents(command);
      selection.removeAllRanges();
      selection.addRange(range);
      command.focus();
      $('#bootstrap-copy-status').textContent = 'Could not copy automatically. The command is selected; copy it manually with Ctrl+C or ⌘C.';
    }
  });
  $('#logout').addEventListener('click', async () => { clearPairingCode(); closeWizard(); try { await api('/api/logout', { method: 'POST' }); location.reload(); } catch (error) { setNotice(error.message, true); } });
  const makeField = (labelText, value, type = 'text') => { const label = document.createElement('label'); label.append(document.createTextNode(labelText)); const input = document.createElement('input'); input.value = value || ''; input.type = type; input.maxLength = 300; label.append(input); return {label,input}; };
  const watchedNames = info => info.apps.filter(app => app.enabled).map(app => app.label || app.package_id);
  const loadInventory = async device => {
    const list = $('.app-list', device); const deviceId = device.dataset.id;
    list.textContent = 'Loading installed third-party apps…';
    try {
      const apps = await api(`/api/devices/${encodeURIComponent(deviceId)}/inventory`);
      if (!device.isConnected || device.dataset.id !== deviceId) return;
      list.replaceChildren();
      if (!apps.length) { list.textContent = 'No third-party apps found. System apps are not changed.'; return; }
      for (const app of apps) {
        const row = document.createElement('div'); row.className = 'app-row';
        const label = document.createElement('label'); const check = document.createElement('input'); check.type = 'checkbox'; check.checked = app.watched; check.setAttribute('aria-label', `Watch ${app.label}`);
        const title = document.createElement('span'); title.textContent = `${app.label} · ${app.version_name || 'version unknown'}`;
        const packageId = document.createElement('code'); packageId.className = 'package'; packageId.textContent = app.package_id;
        label.append(check, title); const details = document.createElement('div'); details.append(packageId);
        const actions = document.createElement('div'); actions.className = 'app-actions';
        const initialLabel = document.createElement('label'); const initial = document.createElement('input'); initial.type = 'checkbox'; initial.checked = false;
        initialLabel.append(initial, document.createTextNode(' Compile once when enabling watch')); initialLabel.className = 'muted'; details.append(initialLabel);
        const overrideLabel = document.createElement('label'); const override = document.createElement('input'); override.type = 'checkbox'; override.checked = false;
        overrideLabel.append(override, document.createTextNode(' Foreground override for this job')); overrideLabel.className = 'muted';
        const compile = button('Queue compile', async () => {
          try { await api(`/api/devices/${encodeURIComponent(deviceId)}/compile`, {method:'POST',body:{package_id:app.package_id,foreground_override:override.checked}}); override.checked=false; setNotice('Manual compile queued. The scheduler will report its current wait reason.'); await loadStatus(); }
          catch (error) { setNotice(error.message, true); }
        }); compile.title = 'Queue this watched app for a fixed speed compile';
        actions.append(overrideLabel, compile); row.append(label, details, actions); list.append(row);
        check.addEventListener('change', async () => {
          check.disabled = true;
          try {
            if (check.checked) await api(`/api/devices/${encodeURIComponent(deviceId)}/watch`, {method:'POST',body:{package_id:app.package_id,initial_compile:initial.checked}});
            else await api(`/api/devices/${encodeURIComponent(deviceId)}/watch/${encodeURIComponent(app.package_id)}`, {method:'DELETE'});
            app.watched = check.checked;
            const deviceInfo=state.devices.find(item=>item.id===deviceId); let savedApp=deviceInfo?.apps.find(item=>item.package_id===app.package_id);
            if(deviceInfo && check.checked && !savedApp){savedApp={package_id:app.package_id,label:app.label,enabled:true};deviceInfo.apps.push(savedApp);}
            else if(savedApp) savedApp.enabled=check.checked;
            const names=deviceInfo?watchedNames(deviceInfo):[]; $('.watched-summary',device).textContent=names.length?`Watching: ${names.join(', ')}`:'No apps selected';
            setNotice(check.checked ? (initial.checked ? 'Watching enabled with an initial compile queued.' : 'Watching enabled. Current version saved as its baseline; no initial compile was queued.') : 'Watching paused for this app.');
          } catch(error) { check.checked = !check.checked; setNotice(error.message, true); }
          finally { check.disabled = false; }
        });
      }
    } catch(error) { if (device.isConnected && device.dataset.id === deviceId) list.textContent = error.message; }
  };
  const renderDevices = devices => {
    state.devices = devices; const holder = $('#devices'); holder.replaceChildren(); $('#empty-devices').classList.toggle('hidden', devices.length > 0);
    $('#setup-entry').classList.toggle('hidden', wizard.open);
    if (devices.length) { $('#setup-title').textContent = 'Add another TV'; $('#setup-copy').textContent = 'Connect another TV and choose the apps to watch.'; $('#start-setup').textContent = 'Add another TV'; }
    for (const info of devices) {
      const card = document.createElement('article'); card.className = 'device'; card.dataset.id = info.id;
      const head = document.createElement('div'); head.className = 'device-head';
      const identity = document.createElement('div'); const title = document.createElement('h3'); title.textContent = info.name; identity.append(title);
      const badge = document.createElement('span'); badge.className = `badge${info.enabled ? '' : ' off'}`; badge.textContent = info.enabled ? 'Monitoring available' : 'TV paused'; head.append(identity,badge); card.append(head);
      const names = watchedNames(info); const summary = document.createElement('p'); summary.className = 'muted watched-summary'; summary.textContent = names.length ? `Watching: ${names.join(', ')}` : 'No apps selected'; card.append(summary);
      const manage = document.createElement('details'); manage.className = 'device-manage'; const summaryManage = document.createElement('summary'); summaryManage.textContent = 'Manage TV details'; manage.append(summaryManage);
      const serial = document.createElement('p'); serial.className = 'muted'; serial.textContent = `Pinned TV serial: ${info.serial}`; manage.append(serial);
      const endpointField = makeField('Connection IP:port', info.endpoint); endpointField.input.setAttribute('aria-label', `Connection IP:port for ${info.name}`);
      const nameField = makeField('TV name', info.name); nameField.input.maxLength = 80;
      const tools = document.createElement('div'); tools.className='device-tools';
      tools.append(button('Save name and reconnect', async () => { try { await api(`/api/devices/${encodeURIComponent(info.id)}/reconnect`,{method:'POST',body:{endpoint:endpointField.input.value}}); await api(`/api/devices/${encodeURIComponent(info.id)}`,{method:'PATCH',body:{name:nameField.input.value}}); setNotice('TV endpoint verified against its pinned identity and saved.'); await refreshAll(); } catch(error){setNotice(error.message,true);} }));
      tools.append(button(info.enabled ? 'Pause TV' : 'Enable TV', async () => { try { await api(`/api/devices/${encodeURIComponent(info.id)}`,{method:'PATCH',body:{enabled:!info.enabled}}); await refreshAll(); } catch(error){setNotice(error.message,true);} }));
      tools.append(button('Forget TV', async () => { if (!window.confirm(`Forget ${info.name} and its saved app/job history?`)) return; try { await api(`/api/devices/${encodeURIComponent(info.id)}`,{method:'DELETE'}); await refreshAll(); } catch(error){setNotice(error.message,true);} }));
      const fields=document.createElement('div');fields.className='form-grid compact';fields.append(nameField.label,endpointField.label); manage.append(fields,tools);
      const appsDetails=document.createElement('details'); appsDetails.className='app-management'; const appsSummary=document.createElement('summary'); appsSummary.textContent='Manage watched apps'; appsDetails.append(appsSummary);
      const apps=document.createElement('div');apps.className='app-list';apps.textContent='App inventory has not been loaded.';appsDetails.append(apps);
      appsDetails.append(button('Load fresh app inventory',()=>loadInventory(card)));
      card.append(manage,appsDetails); holder.append(card);
    }
  };
  const loadStatus = async () => {
    const session = state.session; const status = await api('/api/status'); if (session !== state.session || !state.authenticated) return;
    state.monitoringEnabled = status.monitoring_enabled;
    $('#monitor-status').textContent = status.monitoring_enabled ? 'Monitoring is on. Automatic checks follow the saved schedule.' : 'Monitoring is paused. Setup and app selection do not change this setting.';
    $('#monitor-copy').textContent = status.monitoring_enabled ? 'Automatic checks are enabled. Jobs wait when a TV is offline, active, or outside its maintenance window.' : 'Automatic checks are paused. Explicit manual jobs may still run while paused.';
    $('#monitor-toggle').textContent = status.monitoring_enabled ? 'Pause monitoring' : 'Resume monitoring';
    const statusNames = {pending:'Waiting',running:'Running',succeeded:'Succeeded',failed:'Failed',superseded:'Superseded',cancelled:'Cancelled'};
    const body=$('#jobs');body.replaceChildren();
    for(const job of status.jobs){const row=document.createElement('tr');
      const device=state.devices.find(item=>item.id===job.device_id); const values=[device?.name||job.device_id,job.package_id,statusNames[job.state]||job.state,job.reason|| (job.manual?'Manual job queued':'No current wait reason'),job.updated_at];
      values.forEach((value,index)=>{const cell=document.createElement('td');if(index===2){cell.textContent=value||'—';cell.className=`status ${job.state}`;}else cell.textContent=value||'—';if(index===3){const eventList=document.createElement('div');eventList.className='muted';cell.append(button('View events',async()=>{if(eventList.textContent){eventList.textContent='';return;}try{const events=await api(`/api/jobs/${encodeURIComponent(job.id)}/events`);eventList.textContent=events.map(item=>`${new Date(item.created_at).toLocaleString()} · ${item.event}${item.detail?`: ${item.detail}`:''}`).join('\n')||'No event details.';}catch(error){eventList.textContent=error.message;}}));cell.append(eventList);}row.append(cell);});body.append(row);}
    $('#poll-status').textContent = status.last_poll_at ? `Last check: ${new Date(status.last_poll_at).toLocaleString()}${status.last_poll_error ? ` · ${status.last_poll_error}` : ''}` : 'No scheduler check has completed yet.';
    const cfg=status.maintenance_window||{}; const form=$('#settings-form');
    if (!form.dataset.dirty && !form.contains(document.activeElement)) {form.elements.poll_interval_seconds.value=status.poll_interval_seconds;form.elements.max_attempts.value=status.max_attempts;form.elements.window_start.value=cfg.start||'';form.elements.window_end.value=cfg.end||'';form.elements.timezone.value=cfg.timezone||'UTC';}
    if (wizard.open && wizard.step === 4) renderReview();
  };
  const refreshAll = async (session = state.session) => { try { const devices=await api('/api/devices'); if (session !== state.session || !state.authenticated) return; renderDevices(devices); await loadStatus(); } catch(error){if (session === state.session) setNotice(error.message,true);} };

  const wizardBox = $('#wizard');
  const currentRun = run => wizard.open && wizard.run === run && state.authenticated;
  const errorText = error => error?.message || 'Request failed. Try again.';
  const setWizardBusy = busy => {
    wizard.busy=busy;
    $('#wizard-back').disabled=busy || wizard.step===0 || Boolean(wizard.device && wizard.step===3);
    $('#wizard-next').disabled=busy;
    $('#wizard-cancel').disabled=busy;
    $('#wizard-discover').disabled=busy;
    $('#wizard-app-save').disabled=busy;
    $('#wizard-app-skip').disabled=busy;
    $('#wizard-app-retry').disabled=busy;
    $('#wizard-monitoring').querySelectorAll('button').forEach(button=>{button.disabled=busy;});
    $('#wizard-apps').querySelectorAll('input[type="checkbox"]').forEach(input=>{input.disabled=busy || wizard.saved.has(input.value);});
    $('#wizard-next').textContent=busy?'Working…':(wizard.step===3?'Save selected apps':wizard.step===4?'Finish':'Continue');
  };
  const setDashboardForWizard = open => document.querySelectorAll('.dashboard-content').forEach(section=>section.classList.toggle('hidden',open));
  const closeWizard = (message = '') => {
    wizard.run += 1; wizard.open=false; wizard.busy=false; wizardBox.classList.add('hidden'); setDashboardForWizard(false); $('#wizard-error').textContent=''; $('#wizard-app-error').textContent=''; clearPairingCode();
    if (message) setNotice(message);
    if(state.authenticated) $('#start-setup').focus({preventScroll:true});
  };
  const stepOrder = () => wizard.pairingMode === 'new' ? [0,1,2,3,4] : [0,1,3,4];
  const visibleIndex = () => stepOrder().indexOf(wizard.step);
  const showWizardStep = (step, focus = true) => {
    wizard.step=step;
    document.querySelectorAll('.wizard-step').forEach(section => section.classList.toggle('hidden', Number(section.dataset.step)!==step));
    const order=stepOrder(); $('#wizard-progress').textContent=`Step ${visibleIndex()+1} of ${order.length}`;
    const canGoBack=step!==0 && !(wizard.device && step===3);
    $('#wizard-back').classList.toggle('hidden',!canGoBack); $('#wizard-back').disabled=wizard.busy || !canGoBack;
    $('#wizard-next').classList.toggle('hidden',step===3); $('#wizard-next').textContent=step===4?'Finish':'Continue';
    if(step===3) $('#wizard-app-actions').classList.remove('hidden');
    else $('#wizard-app-actions').classList.add('hidden');
    if(step===4) renderReview();
    if(focus) $('#wizard-title').focus();
  };
  const openWizard = async () => {
    if(!state.authenticated || wizard.open || wizard.busy) return;
    wizard.open=true; wizard.run += 1; wizardBox.dataset.run=String(wizard.run); wizard.step=0; wizard.device=null; wizard.inventory=[]; wizard.saved=new Set(); wizard.pairingMode='new';
    $('#wizard-name').value=''; $('#wizard-endpoint').value=''; $('#wizard-pair-endpoint').value=''; clearPairingCode();
    $('#wizard-services').replaceChildren(); $('#wizard-apps').replaceChildren(); $('#wizard-error').textContent=''; $('#wizard-app-error').textContent='';
    $('input[name="pairing-mode"][value="new"]').checked=true;
    wizardBox.classList.remove('hidden'); setDashboardForWizard(true); showWizardStep(0); setWizardBusy(false);
    await refreshAll();
  };
  const createWizardDevice = async run => {
    if(wizard.device) { await loadWizardInventory(run); return; }
    const payload={name:$('#wizard-name').value.trim(),endpoint:$('#wizard-endpoint').value.trim()};
    if(wizard.pairingMode==='new'){payload.pairing_endpoint=$('#wizard-pair-endpoint').value.trim();payload.pairing_code=$('#wizard-code').value;}
    clearPairingCode(); setWizardBusy(true); $('#wizard-error').textContent='';
    let device;
    try {
      device=await api(wizard.pairingMode==='new'?'/api/pair':'/api/devices',{method:'POST',body:payload});
    } catch(error) { if(currentRun(run)) $('#wizard-error').textContent=`${errorText(error)} Check the TV’s current IP, port, and Wireless debugging state, then try again.`; if(currentRun(run)) setWizardBusy(false); return; }
    if(!currentRun(run)) return;
    wizard.device=device; wizard.saved=new Set(); showWizardStep(3,false);
    try { const devices=await api('/api/devices'); if(currentRun(run)) renderDevices(devices); }
    catch(error) { if(currentRun(run)) $('#wizard-error').textContent=`${errorText(error)} The TV is saved. Retry loading apps or continue later from your TV list.`; }
    await loadWizardInventory(run);
  };
  const loadWizardInventory = async run => {
    if(!wizard.device || !currentRun(run)) return;
    setWizardBusy(true); $('#wizard-app-error').textContent=''; $('#wizard-apps').textContent='Loading installed apps…';
    try {
      const apps=await api(`/api/devices/${encodeURIComponent(wizard.device.id)}/inventory`); if(!currentRun(run)) return;
      $('#wizard-error').textContent=''; wizard.inventory=apps; for(const app of apps) if(app.watched) wizard.saved.add(app.package_id); renderWizardApps(); showWizardStep(3); $('#wizard-app-retry').classList.add('hidden');
      if(!apps.length) $('#wizard-apps').textContent='No third-party apps found. You can finish setup without selecting apps.';
    } catch(error) { if(currentRun(run)) { $('#wizard-apps').textContent=''; $('#wizard-app-error').textContent=`${errorText(error)} The TV is saved. Retry loading apps or skip for now.`; $('#wizard-app-actions').classList.remove('hidden'); $('#wizard-app-retry').classList.remove('hidden'); $('#wizard-next').classList.add('hidden'); } }
    finally { if(currentRun(run)) setWizardBusy(false); }
  };
  const renderWizardApps = () => {
    const holder=$('#wizard-apps'); holder.replaceChildren();
    for(const app of wizard.inventory){
      const row=document.createElement('label');row.className='wizard-app';
      const check=document.createElement('input');check.type='checkbox';check.value=app.package_id;check.checked=app.watched || wizard.saved.has(app.package_id);check.disabled=wizard.saved.has(app.package_id);
      const copy=document.createElement('span');copy.textContent=`${app.label} · ${app.package_id}${app.version_name?` · ${app.version_name}`:''}`;
      row.append(check,copy);holder.append(row);
    }
    updateWizardAppState();
    const actions=$('#wizard-app-actions'); actions.classList.remove('hidden');
  };
  const updateWizardAppState = () => {
    const saved=wizard.inventory.filter(app=>app.watched || wizard.saved.has(app.package_id)).map(app=>app.label || app.package_id);
    const selected=Array.from($('#wizard-apps').querySelectorAll('input[type="checkbox"]:checked')).map(input=>input.value);
    const remaining=wizard.inventory.filter(app=>selected.includes(app.package_id) && !app.watched && !wizard.saved.has(app.package_id)).map(app=>app.label || app.package_id);
    const parts=[]; if(saved.length) parts.push(`Saved: ${saved.join(', ')}.`); if(remaining.length) parts.push(`Not yet saved: ${remaining.join(', ')}.`);
    $('#wizard-app-state').textContent=parts.join(' ');
  };
  $('#wizard-apps').addEventListener('change', updateWizardAppState);
  const saveWizardApps = async () => {
    if(wizard.busy || !currentRun(wizard.run) || !wizard.device) return;
    const run=wizard.run; const selected=Array.from($('#wizard-apps').querySelectorAll('input[type="checkbox"]:checked')).map(input=>input.value);
    setWizardBusy(true); $('#wizard-app-error').textContent='';
    try {
      for(const packageId of selected){
        if(wizard.saved.has(packageId)) continue;
        try { await api(`/api/devices/${encodeURIComponent(wizard.device.id)}/watch`,{method:'POST',body:{package_id:packageId,initial_compile:false}}); if(!currentRun(run)) return; wizard.saved.add(packageId); for(const input of $('#wizard-apps').querySelectorAll('input[type="checkbox"]')) if(input.value===packageId) input.disabled=true; updateWizardAppState(); }
        catch(error) { if(currentRun(run)) {updateWizardAppState();$('#wizard-app-error').textContent=`Could not save ${packageId}: ${errorText(error)} Retry to save remaining selections or skip for now.`;} return; }
      }
      if(!currentRun(run)) return;
      await refreshAll(); if(!currentRun(run)) return; showWizardStep(4);
    } finally { if(currentRun(run)) setWizardBusy(false); }
  };
  const renderReview = () => {
    const review=$('#wizard-review'); review.replaceChildren();
    const title=document.createElement('p');title.textContent=`${wizard.device?.name || $('#wizard-name').value.trim()} is saved.`;review.append(title);
    const selected=wizard.inventory.filter(app=>app.watched || wizard.saved.has(app.package_id)).map(app=>app.label || app.package_id);
    const apps=document.createElement('p');apps.textContent=selected.length?`Watching: ${selected.join(', ')}`:'No apps selected. You can select them later from Manage watched apps.';review.append(apps);
    const current=document.createElement('p');current.className='muted';current.textContent=state.monitoringEnabled?'Monitoring is currently on. Finish keeps it on.':'Monitoring is currently paused. Finish keeps it paused.';review.append(current);
    const actions=$('#wizard-monitoring');actions.replaceChildren();
    if(!state.monitoringEnabled) { const resume=button('Resume monitoring',async()=>{if(wizard.busy || !currentRun(wizard.run))return;const run=wizard.run;setWizardBusy(true);$('#wizard-error').textContent='';try{await api('/api/monitoring',{method:'PUT',body:{enabled:true}});if(!currentRun(run))return;state.monitoringEnabled=true;await loadStatus();renderReview();}catch(error){if(currentRun(run))$('#wizard-error').textContent=`${errorText(error)} The monitoring state could not be confirmed. Check its current state before retrying.`;}finally{if(currentRun(run))setWizardBusy(false);}},'secondary'); resume.disabled=wizard.busy; actions.append(resume); }
  };
  const advanceWizard = async () => {
    if(wizard.busy) return;
    $('#wizard-error').textContent='';
    if(wizard.step===0){const name=$('#wizard-name');if(!name.value.trim()){name.setCustomValidity('Enter a name for this TV.');name.reportValidity();name.setCustomValidity('');return;}wizard.pairingMode=$('input[name="pairing-mode"]:checked').value;showWizardStep(1);return;}
    if(wizard.step===1){const endpoint=$('#wizard-endpoint');if(!endpoint.value.trim()){endpoint.setCustomValidity('Enter the connection IP and port shown on the TV.');endpoint.reportValidity();endpoint.setCustomValidity('');return;}if(wizard.pairingMode==='new')showWizardStep(2);else await createWizardDevice(wizard.run);return;}
    if(wizard.step===2){const pairing=$('#wizard-pair-endpoint'), code=$('#wizard-code');if(!pairing.value.trim()){pairing.setCustomValidity('Enter the pairing IP and port from the pairing popup.');pairing.reportValidity();pairing.setCustomValidity('');return;}if(!/^[0-9]{4,12}$/.test(code.value)){code.setCustomValidity('Enter the 4 to 12 digit pairing code.');code.reportValidity();code.setCustomValidity('');return;}await createWizardDevice(wizard.run);return;}
    if(wizard.step===4) closeWizard('Setup finished. Your monitoring setting was preserved.');
  };
  $('#start-setup').addEventListener('click',openWizard);
  $('#wizard-cancel').addEventListener('click',()=>closeWizard(wizard.device?'Setup closed. The saved TV and selected apps are kept.':'Setup cancelled. No TV was added.'));
  $('#wizard-back').addEventListener('click',()=>{if(wizard.busy)return;if(wizard.device){if(wizard.step===4)showWizardStep(3);return;}const order=stepOrder();const at=visibleIndex();if(at>0)showWizardStep(order[at-1]);});
  $('#wizard-next').addEventListener('click',advanceWizard);
  $('#wizard-app-save').addEventListener('click',saveWizardApps);
  $('#wizard-app-skip').addEventListener('click',()=>{if(wizard.busy)return;showWizardStep(4);});
  $('#wizard-app-retry').addEventListener('click',()=>{if(!wizard.busy)loadWizardInventory(wizard.run);});
  document.querySelectorAll('input[name="pairing-mode"]').forEach(input=>input.addEventListener('change',()=>{wizard.pairingMode=input.value;}));
  $('#wizard-name').addEventListener('input',()=>$('#wizard-name').setCustomValidity(''));
  $('#wizard-endpoint').addEventListener('input',()=>$('#wizard-endpoint').setCustomValidity(''));
  $('#wizard-pair-endpoint').addEventListener('input',()=>$('#wizard-pair-endpoint').setCustomValidity(''));
  $('#wizard-code').addEventListener('input',()=>$('#wizard-code').setCustomValidity(''));
  wizardBox.addEventListener('keydown',event=>{if(event.key==='Enter' && event.target.matches('input:not([type="radio"]):not([type="checkbox"])') && wizard.step!==3){event.preventDefault();advanceWizard();}});
  $('#wizard-discover').addEventListener('click',async()=>{
    const run=wizard.run;const list=$('#wizard-services');list.textContent='Looking for Wireless debugging services…';$('#wizard-discover').disabled=true;
    try{const services=await api('/api/discover',{method:'POST'});if(!currentRun(run))return;list.replaceChildren();if(!services.length){list.textContent='No TLS services found. Confirm Wireless debugging is enabled and the TV is on this network.';return;}
      for(const service of services){const row=document.createElement('div');row.className='service';const text=document.createElement('span');text.textContent=`${service.kind}: ${service.endpoint}`;row.append(text);const target=service.kind==='pairing'?$('#wizard-pair-endpoint'):$('#wizard-endpoint');const action=button(service.kind==='pairing'?'Use pairing endpoint':'Use connection endpoint',()=>{target.value=service.endpoint;target.focus();});row.append(action);list.append(row);}
    }catch(error){if(currentRun(run))list.textContent=`${errorText(error)} Try again or enter the TV’s current endpoint manually.`;}finally{if(currentRun(run))$('#wizard-discover').disabled=false;}
  });

  $('#monitor-toggle')?.addEventListener('click',async()=>{try{const status=await api('/api/status');await api('/api/monitoring',{method:'PUT',body:{enabled:!status.monitoring_enabled}});await loadStatus();}catch(error){setNotice(error.message,true);}});
  $('#refresh').addEventListener('click',()=>refreshAll());
  $('#discover').addEventListener('click',async()=>{const list=$('#services');list.textContent='Looking for Wireless debugging services…';try{const services=await api('/api/discover',{method:'POST'});list.replaceChildren();if(!services.length){list.textContent='No TLS services found. Confirm Wireless debugging is enabled and the TV is on this network.';return;}for(const service of services){const line=document.createElement('div');line.className='service';line.textContent=`${service.kind}: ${service.endpoint}`;list.append(line);}}catch(error){list.textContent=error.message;}});
  $('#pair-form').addEventListener('submit',async event=>{event.preventDefault();const form=event.currentTarget;const fields=formData(form);clearPairingCode();const submit=form.querySelector('[type="submit"]');submit.disabled=true;try{const result=await api('/api/pair',{method:'POST',body:fields});form.reset();setNotice(`Added ${result.name}. Its durable serial and build identity are pinned.`);await refreshAll();}catch(error){setNotice(error.message,true);}finally{submit.disabled=false;}});
  $('#add-form').addEventListener('submit',async event=>{event.preventDefault();const form=event.currentTarget;const submit=form.querySelector('[type="submit"]');submit.disabled=true;try{const result=await api('/api/devices',{method:'POST',body:formData(form)});form.reset();setNotice(`Added ${result.name} after checking its TV identity.`);await refreshAll();}catch(error){setNotice(error.message,true);}finally{submit.disabled=false;}});
  $('#settings-form').addEventListener('input',event=>{event.currentTarget.dataset.dirty='true';});
  $('#settings-form').addEventListener('submit',async event=>{event.preventDefault();const form=event.currentTarget;const d=formData(form);d.poll_interval_seconds=Number(d.poll_interval_seconds);d.max_attempts=Number(d.max_attempts);d.window_start=d.window_start||null;d.window_end=d.window_end||null;try{await api('/api/settings',{method:'PUT',body:d});form.dataset.dirty='';setNotice('Global scheduling settings saved.');await loadStatus();}catch(error){setNotice(error.message,true);}});
  $('#diagnostics').addEventListener('click',async()=>{try{const data=await api('/api/diagnostics');const blob=new Blob([JSON.stringify(data,null,2)],{type:'application/json'});const link=document.createElement('a');link.href=URL.createObjectURL(blob);link.download='android-tv-speed-compiler-diagnostics.json';link.click();URL.revokeObjectURL(link.href);}catch(error){setNotice(error.message,true);}});
  loadSession().catch(error=>{showAuth(true);$('#auth-message').textContent=error.message;});
  window.setInterval(()=>{if(!dashboard.classList.contains('hidden'))loadStatus().catch(error=>{if(error.status===401){clearPairingCode();closeWizard();location.reload();}});},10000);
})();
