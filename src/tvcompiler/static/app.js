(() => {
  const $ = (selector, root = document) => root.querySelector(selector);
  const csrf = () => $('meta[name="csrf-token"]').content;
  const state = { csrf: csrf(), configured: false, authenticated: false, loginEnabled: document.body.dataset.loginEnabled === 'true', tokenRequired: document.body.dataset.tokenRequired === 'true', devices: [], monitoringEnabled: false, session: 0 };
  const wizard = { open: false, run: 0, step: 0, busy: false, device: null, inventory: [], saved: new Set(), connectionMode: 'wireless', pairingMode: 'new', pairingDone: false, autoCompanion: false, companion: null, companionAttempted: false, recovery: false, enableMonitoring: false, queueInitialCompiles: false };
  const authPanel = $('#auth-panel');
  const dashboard = $('#dashboard');
  const notice = $('#notice');
  const setNotice = (message, error = false) => { notice.textContent = message || ''; notice.style.color = error ? 'var(--red)' : 'var(--green)'; };
  const api = async (url, options = {}) => {
    if (options.method && options.method !== 'GET' && !state.loginEnabled) {
      const sessionResponse=await fetch('/api/session',{credentials:'same-origin'});
      const session=sessionResponse.ok?await sessionResponse.json().catch(()=>({})):{};
      if(!sessionResponse.ok || !session.csrf) throw new Error('Could not refresh request verification. Reload this page and try again.');
      state.csrf=session.csrf;
      $('meta[name="csrf-token"]').content=session.csrf;
    }
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
  const clearPairingCode = () => { $('#wizard-code').value = ''; $('#companion-pair-code').value = ''; const manual = $('#pair-form [name="pairing_code"]'); if (manual) manual.value = ''; };
  const showAuth = (configured, tokenRequired = state.tokenRequired) => {
    if (!state.loginEnabled) return;
    state.configured = configured; state.authenticated = false; state.tokenRequired = tokenRequired; state.session += 1;
    closeWizard(); clearPairingCode();
    authPanel.classList.remove('hidden'); dashboard.classList.add('hidden');
    $('#bootstrap-help').classList.toggle('hidden', configured || !tokenRequired);
    $('#bootstrap-copy-status').textContent = '';
    $('#token-label').classList.toggle('hidden', configured || !tokenRequired);
    $('#token').required = !configured && tokenRequired;
    $('#token').disabled = configured || !tokenRequired;
    $('#password').autocomplete = configured ? 'current-password' : 'new-password';
    $('#password').minLength = configured ? 1 : 6;
    $('#auth-title').textContent = configured ? 'Sign in' : 'Set up your local account';
    $('#auth-copy').textContent = configured ? 'Sign in to manage TVs and queued work on this service.' : tokenRequired ? 'Enter the one-time token and choose a strong password of at least 6 characters.' : 'Choose a password of at least 6 characters to create your local account.';
    $('#auth-submit').textContent = configured ? 'Sign in' : 'Create account';
  };
  const loadSession = async () => {
    const data = await api('/api/session');
    state.loginEnabled = data.login_enabled !== false;
    if (data.authenticated && data.csrf) {
      state.session += 1; const session = state.session; state.authenticated = true; state.csrf = data.csrf;
      $('#logout').classList.toggle('hidden', !state.loginEnabled); dashboard.classList.remove('hidden'); authPanel.classList.add('hidden');
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
  const connectionMethodLabel = mode => mode === 'tcpip' ? 'Unencrypted fixed TCP/IP' : 'Encrypted Wireless debugging';
  const makeConnectionModeField = value => {
    const label=document.createElement('label');label.append(document.createTextNode('Connection method'));
    const select=document.createElement('select');select.setAttribute('aria-label','Connection method');
    for(const mode of ['wireless','tcpip']){const option=document.createElement('option');option.value=mode;option.textContent=connectionMethodLabel(mode);select.append(option);}
    select.value=value==='tcpip'?'tcpip':'wireless';label.append(select);return {label,select};
  };
  const watchedNames = info => info.apps.filter(app => app.enabled).map(app => app.label || app.package_id);
  const connectionLabels = {connected:'Connected',offline:'Offline',unauthorized:'Authorization needed',identity_mismatch:'Identity mismatch',adb_unavailable:'ADB unavailable',error:'Connection error',unknown:'Status unknown'};
  const renderConnectionStatus = info => {
    const lines=[];
    const status=info.connection_status || 'unknown';
    const stale=Boolean(info.connection_stale);
    const previous=info.last_known_connection_status;
    lines.push(stale && previous && previous!=='unknown'
      ? `Status not fresh · last known: ${connectionLabels[previous] || previous}`
      : connectionLabels[status] || status);
    if(info.connection_reason) lines.push(info.connection_reason);
    if(info.connection_checked_at){
      const checked=new Date(info.connection_checked_at);
      if(!Number.isNaN(checked.valueOf())) lines.push(`${stale?'Last checked':'Checked'} ${checked.toLocaleString()}`);
    } else lines.push('No connection check recorded yet.');
    const seconds=info.poll_interval_seconds==null?NaN:Number(info.poll_interval_seconds);
    if(Number.isFinite(seconds)) lines.push(`Polling interval: ${seconds} seconds.`);
    if(info.polling_status==='active'){
      const next=info.next_check_at?new Date(info.next_check_at):null;
      lines.push(`Monitoring checks are active.${next&&!Number.isNaN(next.valueOf())?` Next check: ${next.toLocaleString()}.`:''}`);
    } else if(info.polling_status==='paused') lines.push('Automatic polling is paused.');
    else if(info.polling_status==='stopped') lines.push('Automatic polling is stopped.');
    else if(!Number.isFinite(seconds)) lines.push('Polling state is unknown.');
    return lines.join(' ');
  };
  const loadInventory = async device => {
    const list = $('.app-list', device); const deviceId = device.dataset.id;
    list.textContent = 'Loading installed third-party apps…';
    try {
      const apps = await api(`/api/devices/${encodeURIComponent(deviceId)}/inventory`);
      if (!device.isConnected || device.dataset.id !== deviceId) return;
      list.replaceChildren();
      if (!apps.length) { list.textContent = 'No third-party apps found. System apps are not changed.'; return; }
      const search=document.createElement('input');search.type='search';search.placeholder='Search by app name or package ID';search.setAttribute('aria-label','Search watched apps');
      const count=document.createElement('p');count.className='muted';count.setAttribute('role','status');list.append(search,count);
      const rows=[];
      for (const app of apps) {
        const row = document.createElement('div'); row.className = 'app-row'; row.dataset.search=`${app.label||''} ${app.package_id}`.toLocaleLowerCase(); rows.push(row);
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
      const filter=()=>{const query=search.value.trim().toLocaleLowerCase();let visible=0;for(const row of rows){row.classList.toggle('hidden',!row.dataset.search.includes(query));if(!row.classList.contains('hidden'))visible+=1;}count.textContent=query?`${visible} of ${rows.length} apps shown${visible?'':' · No apps match this search.'}`:`${rows.length} apps`;};
      search.addEventListener('input',filter);filter();
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
      const status=info.connection_status || 'unknown';
      const badge = document.createElement('span'); badge.className = `badge connection-badge ${status==='connected'?'connected':status==='unknown'?'off':'warning'}`; badge.textContent = connectionLabels[status] || status; head.append(identity,badge); card.append(head);
      const method=document.createElement('p');method.className='muted connection-method';method.textContent=`Connection method: ${connectionMethodLabel(info.connection_mode)}. This is the selected setup method; encryption is not verified.`;card.append(method);
      const connection=document.createElement('p');connection.className='muted connection-status';connection.dataset.status=status;connection.textContent=`${info.enabled?'':'Monitoring is paused for this TV. '}${renderConnectionStatus(info)}`;card.append(connection);
      const names = watchedNames(info); const summary = document.createElement('p'); summary.className = 'muted watched-summary'; summary.textContent = names.length ? `Watching: ${names.join(', ')}` : 'No apps selected'; card.append(summary);
      const manage = document.createElement('details'); manage.className = 'device-manage'; const summaryManage = document.createElement('summary'); summaryManage.textContent = 'Manage TV details'; manage.append(summaryManage);
      const serial = document.createElement('p'); serial.className = 'muted'; serial.textContent = `Pinned TV serial: ${info.serial}`; manage.append(serial);
      const endpointField = makeField('Connection IP:port', info.endpoint); endpointField.input.setAttribute('aria-label', `Connection IP:port for ${info.name}`);
      const nameField = makeField('TV name', info.name); nameField.input.maxLength = 80;
      const connectionMethod = makeConnectionModeField(info.connection_mode);
      const tools = document.createElement('div'); tools.className='device-tools';
      const companion=info.companion_setup;
      if(companion && companion.phase!=='not_started' && !companion.ready) tools.append(button('Finish companion setup',()=>openCompanionRecovery(info)));
      tools.append(button('Save name and reconnect', async () => { try { await api(`/api/devices/${encodeURIComponent(info.id)}/reconnect`,{method:'POST',body:{endpoint:endpointField.input.value}}); await api(`/api/devices/${encodeURIComponent(info.id)}`,{method:'PATCH',body:{name:nameField.input.value}}); setNotice('TV endpoint verified against its pinned identity and saved.'); await refreshAll(); } catch(error){setNotice(error.message,true);} }));
      tools.append(button('Save connection method', async () => { try { await api(`/api/devices/${encodeURIComponent(info.id)}`,{method:'PATCH',body:{connection_mode:connectionMethod.select.value}}); setNotice('Connection method choice saved. The TV endpoint and pinned identity were not changed.'); await refreshAll(); } catch(error){setNotice(error.message,true);} }));
      tools.append(button(info.enabled ? 'Pause TV' : 'Enable TV', async () => { try { await api(`/api/devices/${encodeURIComponent(info.id)}`,{method:'PATCH',body:{enabled:!info.enabled}}); await refreshAll(); } catch(error){setNotice(error.message,true);} }));
      tools.append(button('Forget TV', async () => { if (!window.confirm(`Forget ${info.name} and its saved app/job history?`)) return; try { await api(`/api/devices/${encodeURIComponent(info.id)}`,{method:'DELETE'}); await refreshAll(); } catch(error){setNotice(error.message,true);} }));
      const fields=document.createElement('div');fields.className='form-grid compact';fields.append(nameField.label,endpointField.label,connectionMethod.label); manage.append(fields,tools);
      const rebootHelp=document.createElement('p');rebootHelp.className='muted';rebootHelp.textContent='Changing the connection method stores your choice only; it does not switch the TV settings or verify encryption. The current endpoint and pinned TV identity stay saved. For fixed TCP/IP, configure compatible TV software or firmware and approve this service’s ADB authorization prompt. The fixed TCP/IP connection is unencrypted; use a trusted private network.';manage.append(rebootHelp);
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
    const adb=status.dependencies?.adb;
    const adbSource=status.source || adb?.source;
    const runtimeStatus=$('#runtime-status');
    runtimeStatus.classList.toggle('runtime-status-warning',Boolean(adb&&!adb.available));
    runtimeStatus.textContent=adb&&!adb.available
      ? `ADB is unavailable in this service runtime. Check its configured executable or container image.${adbSource?` Source: ${adbSource}.`:''}`
      : adb?`ADB is available${adbSource?` · source: ${adbSource}`:''}.`:(adbSource?`ADB source: ${adbSource}.`:'');
    if (!form.dataset.dirty && !form.contains(document.activeElement)) {
      form.elements.poll_interval_seconds.value=status.poll_interval_seconds;
      form.elements.max_attempts.value=status.max_attempts;
      form.elements.window_start.value=cfg.start||'';
      form.elements.window_end.value=cfg.end||'';
      const timezone=cfg.timezone||'UTC';
      if(!Array.from(form.elements.timezone.options).some(option=>option.value===timezone)){
        const option=document.createElement('option');option.value=timezone;option.textContent=timezone;form.elements.timezone.append(option);
      }
      form.elements.timezone.value=timezone;
    }
    if (wizard.open && wizard.step === 5) renderReview();
  };
  const refreshAll = async (session = state.session) => { try { const devices=await api('/api/devices'); if (session !== state.session || !state.authenticated) return; renderDevices(devices); await loadStatus(); } catch(error){if (session === state.session) setNotice(error.message,true);} };

  const wizardBox = $('#wizard');
  const currentRun = run => wizard.open && wizard.run === run && state.authenticated;
  const errorText = error => error?.message || 'Request failed. Try again.';
  const setWizardBusy = busy => {
    wizard.busy=busy;
    const selectedAppCount=wizard.inventory.filter(app=>app.watched || wizard.saved.has(app.package_id)).length;
    $('#wizard-back').disabled=busy || wizard.step===0 || Boolean(wizard.device && (wizard.step===3 || wizard.step===4));
    $('#wizard-next').disabled=busy;
    $('#wizard-cancel').disabled=busy;
    $('#wizard-discover').disabled=busy;
    $('#wizard-discover-pair').disabled=busy;
    $('#wizard-app-save').disabled=busy;
    $('#wizard-app-skip').disabled=busy;
    $('#wizard-app-retry').disabled=busy;
    $('#wizard-enable-monitoring').disabled=busy || state.monitoringEnabled;
    $('#wizard-queue-compiles').disabled=busy || selectedAppCount===0;
    $('#wizard-apps').querySelectorAll('input[type="checkbox"]').forEach(input=>{input.disabled=busy || wizard.saved.has(input.value);});
    $('#wizard-next').textContent=busy?'Working…':(wizard.step===4?'Save selected apps':wizard.step===5?'Finish':'Continue');
  };
  const setDashboardForWizard = open => document.querySelectorAll('.dashboard-content').forEach(section=>section.classList.toggle('hidden',open));
  const closeWizard = (message = '') => {
    wizard.run += 1; wizard.open=false; wizard.busy=false; wizardBox.classList.add('hidden'); setDashboardForWizard(false); $('#wizard-error').textContent=''; $('#wizard-app-error').textContent=''; clearPairingCode();
    if (message) setNotice(message);
    if(state.authenticated) $('#start-setup').focus({preventScroll:true});
  };
  const stepOrder = () => {
    const order=wizard.connectionMode === 'wireless' && wizard.pairingMode === 'new' && !wizard.pairingDone ? [0,1,2] : [0,2];
    if(wizard.autoCompanion) order.push(3);
    return [...order,4,5];
  };
  const updateWizardConnectionMode = () => {
    const fixed=wizard.connectionMode==='tcpip';
    $('#companion-option').classList.toggle('hidden',!fixed);
    const companionBootstrap=fixed && $('#wizard-auto-companion').checked;
    wizard.autoCompanion=companionBootstrap;
    $('#wireless-pairing-options').classList.toggle('hidden',fixed && !companionBootstrap);
    $('#wizard-discover').classList.toggle('hidden',fixed && !companionBootstrap);
    $('#wizard-services').classList.toggle('hidden',fixed && !companionBootstrap);
    $('#wizard-discover-pair').classList.toggle('hidden',fixed && !companionBootstrap);
    $('#wizard-pair-services').classList.toggle('hidden',fixed && !companionBootstrap);
    $('#wizard-connection-copy').textContent=fixed && !companionBootstrap
      ? 'Use the fixed IP:port configured in the TV’s companion app or firmware. Approve this service’s RSA authorization prompt on the TV. Traditional TCP/IP is unencrypted; this setting records your choice and does not change the TV or verify encryption.'
      : companionBootstrap
        ? 'First connect through encrypted Wireless debugging. This temporary TLS endpoint is used to install and configure the companion; setup then asks you to authorize the companion’s separate ADB key.'
      : wizard.pairingDone
        ? 'Pairing succeeded and this service saved its keys. Enter the TV’s current connection IP:port from the main Wireless debugging screen.'
        : wizard.pairingMode==='paired'
          ? 'Enter the current connection IP:port from the TV’s main Wireless debugging screen. “Already paired” means this service has the TV’s pairing keys saved.'
          : 'After pairing, enter the current connection IP:port from the TV’s main Wireless debugging screen. The service can find Wireless debugging endpoints when discovery works.';
  };
  const visibleIndex = () => stepOrder().indexOf(wizard.step);
  const showWizardStep = (step, focus = true) => {
    wizard.step=step;
    document.querySelectorAll('.wizard-step').forEach(section => section.classList.toggle('hidden', Number(section.dataset.step)!==step));
    const order=stepOrder(); $('#wizard-progress').textContent=`Step ${visibleIndex()+1} of ${order.length}`;
    const canGoBack=step!==0 && !(wizard.device && (step===3 || step===4));
    $('#wizard-back').classList.toggle('hidden',!canGoBack); $('#wizard-back').disabled=wizard.busy || !canGoBack;
    $('#wizard-next').classList.toggle('hidden',step===3); $('#wizard-next').textContent=step===5?'Finish':'Continue';
    if(step===4) $('#wizard-app-actions').classList.remove('hidden');
    else $('#wizard-app-actions').classList.add('hidden');
    if(step===5) renderReview();
    if(focus) $('#wizard-title').focus();
  };
  const openWizard = async () => {
    if(!state.authenticated || wizard.open || wizard.busy) return;
    setNotice('');
    wizard.open=true; wizard.run += 1; wizardBox.dataset.run=String(wizard.run); wizard.step=0; wizard.device=null; wizard.inventory=[]; wizard.saved=new Set(); wizard.connectionMode='wireless'; wizard.pairingMode='new'; wizard.pairingDone=false; wizard.autoCompanion=false; wizard.companion=null; wizard.companionAttempted=false; wizard.recovery=false; wizard.enableMonitoring=!state.monitoringEnabled; wizard.queueInitialCompiles=true;
    $('#wizard-name').value=''; $('#wizard-endpoint').value=''; $('#wizard-pair-endpoint').value=''; clearPairingCode();
    $('#wizard-services').replaceChildren(); $('#wizard-pair-services').replaceChildren(); $('#wizard-apps').replaceChildren(); $('#wizard-error').textContent=''; $('#wizard-app-error').textContent='';
    $('input[name="connection-mode"][value="wireless"]').checked=true;
    $('input[name="pairing-mode"][value="new"]').checked=true;
    $('#wizard-auto-companion').checked=false; $('#wizard-companion-port').value='5555';
    updateWizardConnectionMode();
    wizardBox.classList.remove('hidden'); setDashboardForWizard(true); showWizardStep(0); setWizardBusy(false);
    await refreshAll();
  };
  const createWizardDevice = async run => {
    if(wizard.device) { await loadWizardInventory(run); return; }
    const pairing=(wizard.connectionMode==='wireless' || wizard.autoCompanion) && wizard.pairingMode==='new' && !wizard.pairingDone;
    const endpointConnectionMode=wizard.autoCompanion?'wireless':wizard.connectionMode;
    const payload=pairing
      ? {name:$('#wizard-name').value.trim(),pairing_endpoint:$('#wizard-pair-endpoint').value.trim(),pairing_code:$('#wizard-code').value}
      : {name:$('#wizard-name').value.trim(),endpoint:$('#wizard-endpoint').value.trim(),connection_mode:endpointConnectionMode};
    if(pairing) clearPairingCode(); setWizardBusy(true); $('#wizard-error').textContent='';
    let device;
    try {
      device=await api(pairing?'/api/pair':'/api/devices',{method:'POST',body:payload});
    } catch(error) { if(currentRun(run)) $('#wizard-error').textContent=wizard.connectionMode==='tcpip'?`${errorText(error)} Check the TV’s configured fixed IP:port and approve this service’s RSA authorization prompt on the TV.`:`${errorText(error)} Check the TV’s current IP, port, and Wireless debugging state, then try again.`; if(currentRun(run)) setWizardBusy(false); return; }
    if(!currentRun(run)) return;
    if(pairing && device.connection_endpoint_required){wizard.pairingDone=true;updateWizardConnectionMode();$('#wizard-error').textContent='Pairing succeeded. Enter the current connection IP:port below to continue.';$('#wizard-code').value='';showWizardStep(2);setWizardBusy(false);return;}
    if(pairing)wizard.pairingDone=true;
    wizard.device=device; wizard.saved=new Set();
    if(wizard.autoCompanion){showWizardStep(3,false);setWizardBusy(false);await loadCompanionStatus(run);return;}
    showWizardStep(4,false);
    try { const devices=await api('/api/devices'); if(currentRun(run)) renderDevices(devices); }
    catch(error) { if(currentRun(run)) $('#wizard-error').textContent=`${errorText(error)} The TV is saved. Retry loading apps or continue later from your TV list.`; }
    await loadWizardInventory(run);
  };
  const loadWizardInventory = async run => {
    if(!wizard.device || !currentRun(run)) return;
    setWizardBusy(true); $('#wizard-app-error').textContent=''; $('#wizard-apps').textContent='Loading installed apps…';
    try {
      const apps=await api(`/api/devices/${encodeURIComponent(wizard.device.id)}/inventory`); if(!currentRun(run)) return;
      $('#wizard-error').textContent=''; wizard.inventory=apps; for(const app of apps) if(app.watched) wizard.saved.add(app.package_id); renderWizardApps(); showWizardStep(4); $('#wizard-app-retry').classList.add('hidden');
      if(!apps.length) $('#wizard-apps').textContent='No third-party apps found. You can finish setup without selecting apps.';
    } catch(error) { if(currentRun(run)) { $('#wizard-apps').textContent=''; $('#wizard-app-error').textContent=`${errorText(error)} The TV is saved. Retry loading apps or skip for now.`; $('#wizard-app-actions').classList.remove('hidden'); $('#wizard-app-retry').classList.remove('hidden'); $('#wizard-next').classList.add('hidden'); } }
    finally { if(currentRun(run)) setWizardBusy(false); }
  };
  const renderCompanionStatus = status => {
    if(!status || !['not_started','needs_pairing','paired','ready','failed'].includes(status.phase) || typeof status.ready!=='boolean') throw new Error('The service returned an invalid companion status. Refresh and try again.');
    wizard.companion=status;
    const details=status.reason?` ${status.reason}`:'';
    const stateText=$('#companion-state');
    stateText.classList.toggle('message',status.phase==='failed');stateText.classList.toggle('muted',status.phase!=='failed');
    stateText.textContent=status.ready
      ? `Companion setup is complete on port ${status.target_port}.${status.web_disabled?' The setup web page is off.':''}`
      : status.phase==='needs_pairing'
        ? `Enter the separate pairing code shown on the TV. Target port: ${status.target_port}.${details}`
        : status.phase==='paired'
          ? `The companion key is paired. The service is checking its setup.${details}`
          : status.phase==='failed'
            ? `Companion setup needs attention. Retry to resume or repair it.${details}`
            : `Companion setup has not started. Target port: ${status.target_port}.${details}`;
    $('#companion-pair-fields').classList.toggle('hidden',status.phase!=='needs_pairing');
    $('#companion-prepare').textContent=status.phase==='not_started'?'Prepare companion setup':'Retry or resume setup';
    $('#companion-prepare').classList.toggle('hidden',status.phase==='needs_pairing' || status.ready);
    $('#wizard-next').classList.toggle('hidden',!status.ready && wizard.step===3);
  };
  const loadCompanionStatus = async run => {
    try { const status=await api(`/api/devices/${encodeURIComponent(wizard.device.id)}/companion`); if(currentRun(run)) renderCompanionStatus(status); }
    catch(error) { if(currentRun(run)) {$('#companion-state').classList.add('message');$('#companion-state').classList.remove('muted');$('#companion-state').textContent=`${errorText(error)} Retry to check companion setup.`;} }
  };
  const validateCompanionTargetPort = () => {
    const input=$('#wizard-companion-port'); const port=Number(input.value);
    if(!Number.isInteger(port) || port<1024 || port>65535 || port===9093){input.setCustomValidity('Choose a whole number from 1024 to 65535, except 9093.');input.reportValidity();input.setCustomValidity('');return null;}
    return port;
  };
  $('#companion-prepare').addEventListener('click',async()=>{
    if(wizard.busy || !wizard.device || !currentRun(wizard.run))return;
    const targetPort=validateCompanionTargetPort(); if(targetPort===null)return;
    const run=wizard.run;wizard.companionAttempted=true;setWizardBusy(true);$('#companion-state').textContent='Preparing the companion through the verified Wireless debugging connection…';
    try{const status=await api(`/api/devices/${encodeURIComponent(wizard.device.id)}/companion/prepare`,{method:'POST',body:{consent:true,target_port:targetPort}});if(currentRun(run))renderCompanionStatus(status);}
    catch(error){if(currentRun(run)){$('#companion-state').classList.add('message');$('#companion-state').classList.remove('muted');$('#companion-state').textContent=`${errorText(error)} Retry to resume setup.`;}}
    finally{if(currentRun(run))setWizardBusy(false);}
  });
  $('#companion-pair-submit').addEventListener('click',async()=>{
    if(wizard.busy || !wizard.device || !currentRun(wizard.run))return;
    const code=$('#companion-pair-code'), portInput=$('#companion-pair-port');
    const pairingCode=code.value; code.value='';
    const pairingPort=Number(portInput.value);
    if(!Number.isInteger(pairingPort)||pairingPort<1||pairingPort>65535){portInput.setCustomValidity('Enter the pairing port shown on the TV.');portInput.reportValidity();portInput.setCustomValidity('');return;}
    if(!/^[0-9]{4,12}$/.test(pairingCode)){code.setCustomValidity('Enter the 4 to 12 digit pairing code shown on the TV.');code.reportValidity();code.setCustomValidity('');return;}
    const run=wizard.run;setWizardBusy(true);$('#companion-state').textContent='Submitting the companion pairing code…';
    try{const status=await api(`/api/devices/${encodeURIComponent(wizard.device.id)}/companion/pair`,{method:'POST',body:{pairing_code:pairingCode,pairing_port:pairingPort}});if(currentRun(run))renderCompanionStatus(status);}
    catch(error){if(currentRun(run)){$('#companion-state').classList.add('message');$('#companion-state').classList.remove('muted');$('#companion-state').textContent=`${errorText(error)} The code was cleared. Reopen the TV pairing popup and try again.`;}}
    finally{if(currentRun(run))setWizardBusy(false);}
  });
  const openCompanionRecovery = async info => {
    if(!state.authenticated || wizard.open || wizard.busy)return;
    wizard.open=true;wizard.run+=1;wizardBox.dataset.run=String(wizard.run);wizard.device=info;wizard.inventory=[];wizard.saved=new Set();wizard.connectionMode='tcpip';wizard.pairingMode='paired';wizard.pairingDone=true;wizard.autoCompanion=true;wizard.companion=info.companion_setup;wizard.companionAttempted=info.companion_setup?.phase!=='not_started';wizard.recovery=true;wizard.enableMonitoring=!state.monitoringEnabled;wizard.queueInitialCompiles=true;
    setNotice('');
    $('#wizard-name').value=info.name;$('#wizard-endpoint').value=info.endpoint;$('#wizard-companion-port').value=String(info.companion_setup?.target_port||5555);$('#wizard-auto-companion').checked=true;$('#wizard-error').textContent='';$('#wizard-app-error').textContent='';$('#companion-pair-code').value='';$('#companion-pair-port').value='';
    updateWizardConnectionMode();wizardBox.classList.remove('hidden');setDashboardForWizard(true);showWizardStep(3);setWizardBusy(false);await loadCompanionStatus(wizard.run);
  };
  const renderWizardApps = () => {
    const holder=$('#wizard-apps'); holder.replaceChildren();
    const search=document.createElement('input');search.type='search';search.placeholder='Search by app name or package ID';search.setAttribute('aria-label','Search installed apps');
    const count=document.createElement('p');count.className='muted';count.setAttribute('role','status');holder.append(search,count);
    const rows=[];
    for(const app of wizard.inventory){
      const row=document.createElement('label');row.className='wizard-app';row.dataset.search=`${app.label||''} ${app.package_id}`.toLocaleLowerCase();rows.push(row);
      const check=document.createElement('input');check.type='checkbox';check.value=app.package_id;check.checked=app.watched || wizard.saved.has(app.package_id);check.disabled=wizard.saved.has(app.package_id);
      const copy=document.createElement('span');copy.textContent=`${app.label} · ${app.package_id}${app.version_name?` · ${app.version_name}`:''}`;
      row.append(check,copy);holder.append(row);
    }
    const filter=()=>{const query=search.value.trim().toLocaleLowerCase();let visible=0;for(const row of rows){row.classList.toggle('hidden',!row.dataset.search.includes(query));if(!row.classList.contains('hidden'))visible+=1;}count.textContent=query?`${visible} of ${rows.length} apps shown${visible?'':' · No apps match this search.'}`:`${rows.length} apps`;};search.addEventListener('input',filter);filter();
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
      await refreshAll(); if(!currentRun(run)) return; showWizardStep(5);
    } finally { if(currentRun(run)) setWizardBusy(false); }
  };
  const renderReview = () => {
    const review=$('#wizard-review'); review.replaceChildren();
    const title=document.createElement('p');title.textContent=`${wizard.device?.name || $('#wizard-name').value.trim()} is saved.`;review.append(title);
    const selected=wizard.inventory.filter(app=>app.watched || wizard.saved.has(app.package_id)).map(app=>app.label || app.package_id);
    const apps=document.createElement('p');apps.textContent=selected.length?`Watching: ${selected.join(', ')}`:'No apps selected. You can select them later from Manage watched apps.';review.append(apps);
    const selectedCount=wizard.inventory.filter(app=>app.watched || wizard.saved.has(app.package_id)).length;
    const current=document.createElement('p');current.className='muted';current.textContent=state.monitoringEnabled?'Automatic monitoring is already enabled globally; Finish keeps it enabled.':'Automatic monitoring is paused globally. The checked option enables it for all watched TVs.';review.append(current);
    $('#wizard-enable-monitoring').checked=state.monitoringEnabled || wizard.enableMonitoring;
    $('#wizard-enable-monitoring').disabled=state.monitoringEnabled || wizard.busy;
    $('#wizard-queue-compiles').checked=wizard.queueInitialCompiles;
    $('#wizard-queue-compiles').disabled=wizard.busy || selectedCount===0;
  };
  const finishWizard = async () => {
    if(wizard.busy || !currentRun(wizard.run) || !wizard.device)return;
    const run=wizard.run;
    const packageIds=wizard.queueInitialCompiles
      ? wizard.inventory.filter(app=>app.watched || wizard.saved.has(app.package_id)).map(app=>app.package_id)
      : [];
    const enableMonitoring=wizard.enableMonitoring && !state.monitoringEnabled;
    setWizardBusy(true);$('#wizard-error').textContent='';
    try{
      const result=await api(`/api/devices/${encodeURIComponent(wizard.device.id)}/finish`,{method:'POST',body:{package_ids:packageIds,queue_initial_compiles:wizard.queueInitialCompiles,enable_monitoring:enableMonitoring,...(wizard.autoCompanion?{require_companion:true}:{})}});
      if(!currentRun(run))return;
      state.monitoringEnabled=result.monitoring_enabled;
      const queued=result.jobs.filter(job=>job.status==='queued').length;
      const existing=result.jobs.filter(job=>job.status==='already_queued').length;
      const completed=result.jobs.filter(job=>job.status==='already_succeeded').length;
      const failed=result.jobs.filter(job=>job.status==='already_failed').length;
      const pieces=[];
      if(enableMonitoring)pieces.push('Automatic monitoring enabled for all watched TVs');
      else if(state.monitoringEnabled)pieces.push('Automatic monitoring remains enabled');
      else pieces.push('Automatic monitoring remains paused');
      if(wizard.queueInitialCompiles)pieces.push(`${queued} new compile job${queued===1?'':'s'} queued${existing?`, ${existing} already queued`:''}${completed?`, ${completed} already succeeded`:''}${failed?`, ${failed} already failed and not retried`:''}`);
      else pieces.push('No initial compiles queued');
      await refreshAll();if(!currentRun(run))return;closeWizard(`${pieces.join('. ')}. Check the Run log for wait reasons and results.`);
    }catch(error){if(currentRun(run))$('#wizard-error').textContent=`${errorText(error)} Your Finish choices are still selected. You can retry safely; duplicate or completed fingerprints will not be queued again.`;}
    finally{if(currentRun(run))setWizardBusy(false);}
  };
  const advanceWizard = async () => {
    if(wizard.busy) return;
    $('#wizard-error').textContent='';
    if(wizard.step===0){const name=$('#wizard-name');if(!name.value.trim()){name.setCustomValidity('Enter a name for this TV.');name.reportValidity();name.setCustomValidity('');return;}wizard.connectionMode=$('input[name="connection-mode"]:checked').value;wizard.pairingMode=$('input[name="pairing-mode"]:checked')?.value||'new';updateWizardConnectionMode();if(wizard.autoCompanion && validateCompanionTargetPort()===null)return;showWizardStep((wizard.connectionMode==='wireless' || wizard.autoCompanion) && wizard.pairingMode==='new' && !wizard.pairingDone?1:2);return;}
    if(wizard.step===1){const pairing=$('#wizard-pair-endpoint'), code=$('#wizard-code');if(!pairing.value.trim()){pairing.setCustomValidity('Enter the pairing IP and port from the pairing popup.');pairing.reportValidity();pairing.setCustomValidity('');return;}if(!/^[0-9]{4,12}$/.test(code.value)){code.setCustomValidity('Enter the 4 to 12 digit pairing code.');code.reportValidity();code.setCustomValidity('');return;}await createWizardDevice(wizard.run);return;}
    if(wizard.step===2){const endpoint=$('#wizard-endpoint');if(!endpoint.value.trim()){endpoint.setCustomValidity('Enter the connection IP and port shown on the TV.');endpoint.reportValidity();endpoint.setCustomValidity('');return;}await createWizardDevice(wizard.run);return;}
    if(wizard.step===3){if(wizard.companion?.ready) await loadWizardInventory(wizard.run);return;}
    if(wizard.step===5) await finishWizard();
  };
  $('#start-setup').addEventListener('click',openWizard);
  $('#wizard-cancel').addEventListener('click',()=>closeWizard(wizard.autoCompanion && wizard.companion && !wizard.companion.ready && wizard.companionAttempted?'Setup closed. Open adb-auto-enable on the TV and turn off “Enable Web Interface on Boot” to stop its unauthenticated setup server from being reachable on your LAN.':wizard.device?'Setup closed. The saved TV and selected apps are kept.':'Setup cancelled. No TV was added.'));
  $('#wizard-back').addEventListener('click',()=>{if(wizard.busy)return;if(wizard.device){if(wizard.step===5)showWizardStep(4);return;}const order=stepOrder();const at=visibleIndex();if(at>0)showWizardStep(order[at-1]);});
  $('#wizard-next').addEventListener('click',advanceWizard);
  $('#wizard-app-save').addEventListener('click',saveWizardApps);
  $('#wizard-app-skip').addEventListener('click',()=>{if(wizard.busy)return;showWizardStep(5);});
  $('#wizard-app-retry').addEventListener('click',()=>{if(!wizard.busy)loadWizardInventory(wizard.run);});
  document.querySelectorAll('input[name="connection-mode"]').forEach(input=>input.addEventListener('change',()=>{wizard.connectionMode=input.value;updateWizardConnectionMode();if(wizard.step===0)showWizardStep(0,false);}));
  $('#wizard-auto-companion').addEventListener('change',()=>{updateWizardConnectionMode();if(wizard.step===0)showWizardStep(0,false);});
  document.querySelectorAll('input[name="pairing-mode"]').forEach(input=>input.addEventListener('change',()=>{wizard.pairingMode=input.value;updateWizardConnectionMode();}));
  $('#wizard-name').addEventListener('input',()=>$('#wizard-name').setCustomValidity(''));
  $('#wizard-endpoint').addEventListener('input',()=>$('#wizard-endpoint').setCustomValidity(''));
  $('#wizard-pair-endpoint').addEventListener('input',()=>$('#wizard-pair-endpoint').setCustomValidity(''));
  $('#wizard-code').addEventListener('input',()=>$('#wizard-code').setCustomValidity(''));
  $('#wizard-enable-monitoring').addEventListener('change',event=>{wizard.enableMonitoring=event.currentTarget.checked;});
  $('#wizard-queue-compiles').addEventListener('change',event=>{wizard.queueInitialCompiles=event.currentTarget.checked;});
  wizardBox.addEventListener('keydown',event=>{if(event.key==='Enter' && event.target.matches('input:not([type="radio"]):not([type="checkbox"])') && wizard.step!==3 && wizard.step!==4){event.preventDefault();advanceWizard();}});
  const discoverWizard = (buttonId, listId, kind) => $(`#${buttonId}`).addEventListener('click',async()=>{
    const run=wizard.run;const list=$(`#${listId}`);list.textContent='Looking for Wireless debugging services…';$('#wizard-discover').disabled=true;$('#wizard-discover-pair').disabled=true;
    try{const services=await api('/api/discover',{method:'POST'});if(!currentRun(run))return;list.replaceChildren();const matches=services.filter(service=>service.kind===kind);if(!matches.length){list.textContent='No matching endpoint found. Confirm Wireless debugging is enabled and enter the current IP:port manually.';return;}
      for(const service of matches){const row=document.createElement('div');row.className='service';const text=document.createElement('span');text.textContent=`${service.kind}: ${service.endpoint}`;row.append(text);const target=kind==='pairing'?$('#wizard-pair-endpoint'):$('#wizard-endpoint');const action=button(kind==='pairing'?'Use pairing endpoint':'Use connection endpoint',()=>{target.value=service.endpoint;target.focus();});row.append(action);list.append(row);}
    }catch(error){if(currentRun(run))list.textContent=`${errorText(error)} Try again or enter the TV’s current endpoint manually.`;}finally{if(currentRun(run)){const busy=wizard.busy;$('#wizard-discover').disabled=busy;$('#wizard-discover-pair').disabled=busy;}}
  });
  discoverWizard('wizard-discover','wizard-services','connect');
  discoverWizard('wizard-discover-pair','wizard-pair-services','pairing');

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
