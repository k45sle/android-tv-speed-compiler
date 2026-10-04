(() => {
  const $ = (selector, root = document) => root.querySelector(selector);
  const csrf = () => $('meta[name="csrf-token"]').content;
  const state = { csrf: csrf(), configured: false, devices: [] };
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
  const showAuth = (configured) => {
    state.configured = configured;
    authPanel.classList.remove('hidden'); dashboard.classList.add('hidden');
    $('#token-label').classList.toggle('hidden', configured);
    $('#token').required = !configured;
    $('#password').autocomplete = configured ? 'current-password' : 'new-password';
    $('#password').minLength = configured ? 1 : 12;
    $('#auth-title').textContent = configured ? 'Sign in' : 'Set up your local account';
    $('#auth-copy').textContent = configured ? 'Sign in to manage TVs and queued work on this service.' : 'Enter the one-time token from instance/bootstrap.token and choose a strong password of at least 12 characters.';
    $('#auth-submit').textContent = configured ? 'Sign in' : 'Create account';
  };
  const loadSession = async () => {
    const data = await api('/api/session');
    if (data.authenticated && data.csrf) { state.csrf = data.csrf; $('#logout').classList.remove('hidden'); dashboard.classList.remove('hidden'); authPanel.classList.add('hidden'); await refreshAll(); }
    else showAuth(data.configured);
  };
  $('#auth-form').addEventListener('submit', async event => {
    event.preventDefault(); const fields = formData(event.currentTarget); fields.csrf = state.csrf;
    try {
      const login = state.configured;
      if (login) delete fields.token;
      const result = await api(login ? '/api/login' : '/api/setup', { method: 'POST', body: fields });
      state.csrf = result.csrf; $('meta[name="csrf-token"]').content = result.csrf;
      $('#password').value = ''; $('#token').value = '';
      await loadSession();
    }
    catch (error) { $('#auth-message').textContent = error.message; }
  });
  $('#logout').addEventListener('click', async () => { try { await api('/api/logout', { method: 'POST' }); location.reload(); } catch (error) { setNotice(error.message, true); } });
  const makeField = (labelText, value, type = 'text') => { const label = document.createElement('label'); label.append(document.createTextNode(labelText)); const input = document.createElement('input'); input.value = value || ''; input.type = type; input.maxLength = 300; label.append(input); return {label,input}; };
  const loadInventory = async device => {
    const list = $('.app-list', device);
    list.textContent = 'Loading installed third-party apps…';
    try {
      const apps = await api(`/api/devices/${encodeURIComponent(device.dataset.id)}/inventory`);
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
        initialLabel.append(initial, document.createTextNode(' Compile once when enabling watch'));
        initialLabel.className = 'muted'; details.append(initialLabel);
        const overrideLabel = document.createElement('label'); const override = document.createElement('input'); override.type = 'checkbox'; override.checked = false;
        overrideLabel.append(override, document.createTextNode(' Foreground override for this job'));
        overrideLabel.className = 'muted';
        const compile = button('Queue compile', async () => {
          try { await api(`/api/devices/${device.dataset.id}/compile`, {method:'POST',body:{package_id:app.package_id,foreground_override:override.checked}}); override.checked=false; setNotice('Manual compile queued. The scheduler will report its current wait reason.'); await loadStatus(); }
          catch (error) { setNotice(error.message, true); }
        }, 'secondary'); compile.title = 'Queue this watched app for a fixed speed compile';
        actions.append(overrideLabel, compile); row.append(label, details, actions); list.append(row);
        check.addEventListener('change', async () => {
          try {
            if (check.checked) await api(`/api/devices/${device.dataset.id}/watch`, {method:'POST',body:{package_id:app.package_id,initial_compile:initial.checked}});
            else await api(`/api/devices/${device.dataset.id}/watch/${encodeURIComponent(app.package_id)}`, {method:'DELETE'});
            app.watched = check.checked; setNotice(check.checked ? (initial.checked ? 'Watching enabled with an initial compile queued.' : 'Watching enabled. Current version saved as its baseline; no initial compile was queued.') : 'Watching paused for this app.');
          } catch(error) { check.checked = !check.checked; setNotice(error.message, true); }
        });
      }
    } catch(error) { list.textContent = error.message; }
  };
  const renderDevices = devices => {
    state.devices = devices; const holder = $('#devices'); holder.replaceChildren(); $('#empty-devices').classList.toggle('hidden', devices.length > 0);
    for (const info of devices) {
      const card = document.createElement('article'); card.className = 'device'; card.dataset.id = info.id;
      const head = document.createElement('div'); head.className = 'device-head';
      const identity = document.createElement('div'); const title = document.createElement('h3'); title.textContent = info.name;
      const serial = document.createElement('p'); serial.className = 'muted'; serial.textContent = `Pinned TV serial: ${info.serial}`; identity.append(title, serial);
      const badge = document.createElement('span'); badge.className = `badge${info.enabled ? '' : ' off'}`; badge.textContent = info.enabled ? 'Monitoring available' : 'TV paused'; head.append(identity,badge); card.append(head);
      const endpointField = makeField('Connection endpoint', info.endpoint); endpointField.input.setAttribute('aria-label', `Connection endpoint for ${info.name}`);
      const nameField = makeField('Display name', info.name); nameField.input.maxLength = 80;
      const tools = document.createElement('div'); tools.className='device-tools';
      tools.append(button('Save name and reconnect endpoint', async () => { try { await api(`/api/devices/${info.id}/reconnect`,{method:'POST',body:{endpoint:endpointField.input.value}}); await api(`/api/devices/${info.id}`,{method:'PATCH',body:{name:nameField.input.value}}); setNotice('TV endpoint verified against its pinned identity and saved.'); await refreshAll(); } catch(error){setNotice(error.message,true);} }));
      tools.append(button(info.enabled ? 'Pause TV' : 'Enable TV', async () => { try { await api(`/api/devices/${info.id}`,{method:'PATCH',body:{enabled:!info.enabled}}); await refreshAll(); } catch(error){setNotice(error.message,true);} }));
      tools.append(button('Forget TV', async () => { if (!window.confirm(`Forget ${info.name} and its saved app/job history?`)) return; try { await api(`/api/devices/${info.id}`,{method:'DELETE'}); await refreshAll(); } catch(error){setNotice(error.message,true);} }));
      const fields=document.createElement('div');fields.className='form-grid compact';fields.append(nameField.label,endpointField.label); card.append(fields,tools);
      const apps=document.createElement('div');apps.className='app-list';apps.textContent='App inventory has not been loaded.'; card.append(apps);
      tools.append(button('Load fresh app inventory',()=>loadInventory(card)));
      holder.append(card);
    }
  };
  const loadStatus = async () => {
    const status = await api('/api/status');
    $('#monitor-title').textContent = status.monitoring_enabled ? 'Monitoring active' : 'Monitoring paused';
    $('#monitor-copy').textContent = status.monitoring_enabled ? 'Automatic checks are enabled. Jobs wait when a TV is offline, active, or outside its maintenance window.' : 'Automatic checks are paused. Explicit manual jobs may still run while paused.';
    $('#monitor-toggle').textContent = status.monitoring_enabled ? 'Pause monitoring' : 'Resume monitoring';
    const statusNames = {pending:'Waiting',running:'Running',succeeded:'Succeeded',failed:'Failed',superseded:'Superseded',cancelled:'Cancelled'};
    const body=$('#jobs');body.replaceChildren();
    for(const job of status.jobs){const row=document.createElement('tr');
      const device=state.devices.find(item=>item.id===job.device_id); const values=[device?.name||job.device_id,job.package_id,statusNames[job.state]||job.state,job.reason|| (job.manual?'Manual job queued':'No current wait reason'),job.updated_at];
      values.forEach((value,index)=>{
        const cell=document.createElement('td');
        if(index===2){cell.textContent=value||'—';cell.className=`status ${job.state}`;}
        else cell.textContent=value||'—';
        if(index===3){
          const eventList=document.createElement('div');eventList.className='muted';
          cell.append(button('View events',async()=>{
            if(eventList.textContent){eventList.textContent='';return;}
            try{const events=await api(`/api/jobs/${job.id}/events`);eventList.textContent=events.map(item=>`${new Date(item.created_at).toLocaleString()} · ${item.event}${item.detail?`: ${item.detail}`:''}`).join('\n')||'No event details.';}
            catch(error){eventList.textContent=error.message;}
          }));cell.append(eventList);
        }
        row.append(cell);
      });body.append(row);
    }
    $('#poll-status').textContent = status.last_poll_at ? `Last check: ${new Date(status.last_poll_at).toLocaleString()}${status.last_poll_error ? ` · ${status.last_poll_error}` : ''}` : 'No scheduler check has completed yet.';
    const cfg=status.maintenance_window||{};
    if (!$('#settings-form').dataset.dirty && !$('#settings-form').contains(document.activeElement)) {
      $('#settings-form [name="poll_interval_seconds"]').value=status.poll_interval_seconds;
      $('#settings-form [name="max_attempts"]').value=status.max_attempts;
      $('#settings-form [name="window_start"]').value=cfg.start||'';$('#settings-form [name="window_end"]').value=cfg.end||'';
      $('#settings-form [name="timezone"]').value=cfg.timezone||'UTC';
    }
  };
  const refreshAll = async () => { try { renderDevices(await api('/api/devices')); await loadStatus(); } catch(error){setNotice(error.message,true);} };
  $('#monitor-toggle').addEventListener('click',async()=>{try{const status=await api('/api/status');await api('/api/monitoring',{method:'PUT',body:{enabled:!status.monitoring_enabled}});await loadStatus();}catch(error){setNotice(error.message,true);}});
  $('#refresh').addEventListener('click',refreshAll);
  $('#discover').addEventListener('click',async()=>{const list=$('#services');list.textContent='Looking for Wireless debugging services…';try{const services=await api('/api/discover',{method:'POST'});list.replaceChildren();if(!services.length){list.textContent='No TLS services found. Confirm Wireless debugging is enabled and the TV is on this network.';return;}for(const service of services){const line=document.createElement('div');line.className='service';line.textContent=`${service.kind}: ${service.endpoint}`;line.title='Copy the pairing endpoint to Pairing IP:port; the connection service endpoint goes in Connection IP:port.';list.append(line);}}catch(error){list.textContent=error.message;}});
  $('#pair-form').addEventListener('submit',async event=>{event.preventDefault();const form=event.currentTarget;const fields=formData(form);form.elements.pairing_code.value='';try{const result=await api('/api/pair',{method:'POST',body:fields});form.reset();setNotice(`Added ${result.name}. Its durable serial and build identity are pinned.`);await refreshAll();}catch(error){setNotice(error.message,true);}});
  $('#add-form').addEventListener('submit',async event=>{event.preventDefault();const form=event.currentTarget;try{const result=await api('/api/devices',{method:'POST',body:formData(form)});form.reset();setNotice(`Added ${result.name} after checking its TV identity.`);await refreshAll();}catch(error){setNotice(error.message,true);}});
  $('#settings-form').addEventListener('input',event=>{event.currentTarget.dataset.dirty='true';});
  $('#settings-form').addEventListener('submit',async event=>{event.preventDefault();const form=event.currentTarget;const d=formData(form);d.poll_interval_seconds=Number(d.poll_interval_seconds);d.max_attempts=Number(d.max_attempts);d.window_start=d.window_start||null;d.window_end=d.window_end||null;try{await api('/api/settings',{method:'PUT',body:d});form.dataset.dirty='';setNotice('Global scheduling settings saved.');await loadStatus();}catch(error){setNotice(error.message,true);}});
  $('#diagnostics').addEventListener('click',async()=>{try{const data=await api('/api/diagnostics');const blob=new Blob([JSON.stringify(data,null,2)],{type:'application/json'});const link=document.createElement('a');link.href=URL.createObjectURL(blob);link.download='android-tv-speed-compiler-diagnostics.json';link.click();URL.revokeObjectURL(link.href);}catch(error){setNotice(error.message,true);}});
  loadSession().catch(error=>{showAuth(true);$('#auth-message').textContent=error.message;});
  window.setInterval(()=>{if(!dashboard.classList.contains('hidden'))loadStatus().catch(error=>{if(error.status===401)location.reload();});},10000);
})();
