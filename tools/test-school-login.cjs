// Runs the generated Kotlin scripts against the structure of the school's paginated AD FS form.
const vm = require('node:vm');
const assert = require('node:assert/strict');
let input = '';
process.stdin.on('data', data => { input += data; });
process.stdin.on('end', () => {
  const scripts = JSON.parse(input);
  function page({location = 'https://sts.slai.edu.cn/adfs/oauth2/authorize?client_id=test',
                 action = '/adfs/oauth2/authorize?client_id=test', captcha = false, error = false, iframe = false} = {}) {
    let phase = 'username', submitted = 0;
    class Input {
      constructor(form, visible = true) { this.form = form; this.shown = visible; this.disabled = false; this.value = ''; }
      getClientRects() { return this.shown ? [1] : []; }
      dispatchEvent() {}
    }
    Object.defineProperty(Input.prototype, 'value', {get() { return this.raw || ''; }, set(v) { this.raw = v; }});
    const form = {action, querySelectorAll() { return phase === 'password' ? [submit] : []; }};
    const username = new Input(form), password = new Input(form, false);
    const keep = new Input(form); keep.type = 'checkbox'; keep.checked = false;
    keep.click = () => { keep.checked = !keep.checked; };
    const next = {disabled: false, getClientRects() { return phase === 'username' ? [1] : []; },
      click() { phase = 'password'; username.shown = false; password.shown = true; }};
    const submit = {disabled: false, getClientRects() { return phase === 'password' ? [1] : []; },
      click() { submitted++; }};
    const alert = {disabled: false, textContent: error ? 'Login failed' : '', getClientRects() { return [1]; }};
    const captchaInput = new Input(form);
    const document = {
      getElementById(id) { return id === 'nextButton' ? next : id === 'submitButton' ? submit : null; },
      querySelectorAll(selector) {
        if (selector.startsWith('input[name*="captcha"')) return captcha ? [captchaInput] : [];
        if (selector.startsWith('#errorText')) return [alert];
        if (selector.startsWith('#userNameInput')) return [username];
        if (selector === 'input[type="password"]') return [password];
        if (selector.startsWith('#kmsiInput')) return [keep];
        throw Error('Unexpected selector');
      }
    };
    const window = {location: {href: location}};
    window.top = iframe ? {} : window;
    const context = vm.createContext({window, document, URL, HTMLInputElement: Input, Event: class {}});
    return {run: s => vm.runInContext(s, context), username, password, keep, submissions: () => submitted};
  }
  const p = page();
  assert.equal(p.run(scripts.inspect), 'username');
  assert.equal(p.run(scripts.username), 'submitted');
  assert.equal(p.username.value, scripts.account);
  assert.equal(p.password.value, '');
  assert.equal(p.keep.checked, true);
  assert.equal(p.run(scripts.inspect), 'password');
  assert.equal(p.run(scripts.password), 'submitted');
  assert.equal(p.password.value, scripts.secret);
  assert.equal(p.submissions(), 1);
  for (const options of [
    {location: 'http://sts.slai.edu.cn/adfs/ls/'},
    {location: 'https://sts.slai.edu.cn.evil.example/adfs/ls/'},
    {location: 'https://sts.slai.edu.cn:444/adfs/ls/'},
    {location: 'https://sts.slai.edu.cn/adfs/oauth2/authorizeevil'},
    {action: 'https://evil.example/steal'},
    {iframe: true}, {captcha: true}, {error: true}
  ]) {
    const bad = page(options);
    assert.notEqual(bad.run(scripts.username), 'submitted');
    assert.equal(bad.username.value, '');
    assert.equal(bad.password.value, '');
    assert.equal(bad.submissions(), 0);
  }
  process.stdout.write('School login scripts: two-step form and credential boundaries passed\n');
});
