// Model the school's separate AD FS username/password forms and validate the POST payload,
// including the hidden username holder used by the real paginated page. No real credentials.
const vm = require('node:vm');
const assert = require('node:assert/strict');
let input = '';
process.stdin.on('data', data => { input += data; });
process.stdin.on('end', () => {
  const scripts = JSON.parse(input);
  function page({location = 'https://sts.slai.edu.cn/adfs/oauth2/authorize?client_id=test',
                 action = '/adfs/oauth2/authorize?client_id=test', captcha = false, error = false,
                 iframe = false, passwordFirst = false, loading = false} = {}) {
    let phase = passwordFirst ? 'password' : 'username';
    const submissions = [];
    class Input {
      constructor(form, visible = true) { this.form = form; this.shown = visible; this.disabled = false; this.value = ''; }
      getClientRects() { return this.shown ? [1] : []; }
      dispatchEvent() {}
    }
    Object.defineProperty(Input.prototype, 'value', {get() { return this.raw || ''; }, set(v) { this.raw = v; }});
    const usernameForm = {action, querySelectorAll() { return []; }};
    const passwordForm = {action, querySelectorAll() { return [submit]; }};
    const username = new Input(usernameForm, !passwordFirst), password = new Input(passwordForm, passwordFirst);
    const holder = new Input(passwordForm, false);
    // Simulate a remembered account on an already-open password page.
    if (passwordFirst) { username.value = 'previous@example.invalid'; holder.value = username.value; }
    const keep = new Input(usernameForm, !passwordFirst); keep.type = 'checkbox'; keep.checked = false;
    keep.click = () => { keep.checked = !keep.checked; };
    const next = {disabled: false, getClientRects() { return phase === 'username' ? [1] : []; },
      click() {
        assert.notEqual(username.value, '');
        holder.value = username.value; // PaginationManager.updatePagesWithUsername
        phase = 'password'; username.shown = false; keep.shown = false; password.shown = true;
      }};
    const submit = {disabled: false, getClientRects() { return phase === 'password' ? [1] : []; },
      click() {
        // LoginManager reads the hidden original input, not merely the visible password.
        if (!username.value || !password.value) return;
        if (!/[@\\]/.test(username.value)) holder.value = 'slai\\' + username.value;
        submissions.push({username: holder.value, password: password.value});
      }};
    const alert = {disabled: false, textContent: error ? 'Login failed' : '', getClientRects() { return [1]; }};
    const captchaInput = new Input(usernameForm);
    const document = {
      readyState: loading ? 'loading' : 'interactive',
      getElementById(id) { return {nextButton: next, submitButton: submit, userNameInput: username, userNameInputHolder: holder}[id] || null; },
      querySelectorAll(selector) {
        if (selector.startsWith('input[name*="captcha"')) return captcha ? [captchaInput] : [];
        if (selector.startsWith('#errorText')) return [alert];
        if (selector.startsWith('#userNameInput')) return [username, holder];
        if (selector === 'input[type="password"]') return [password];
        if (selector.startsWith('#kmsiInput')) return [keep];
        throw Error('Unexpected selector');
      }
    };
    const window = {location: {href: location}};
    window.top = iframe ? {} : window;
    const context = vm.createContext({window, document, URL, HTMLInputElement: Input, Event: class {},
      getComputedStyle: () => ({visibility: 'visible'})});
    return {run: s => vm.runInContext(s, context), username, password, keep, submissions};
  }
  for (const passwordFirst of [false, true]) {
    const p = page({passwordFirst});
    if (!passwordFirst) {
      assert.equal(p.run(scripts.inspect), 'username');
      assert.equal(p.run(scripts.username), 'submitted');
      assert.equal(p.username.value, scripts.account);
      assert.equal(p.password.value, '');
      assert.equal(p.keep.checked, true);
    }
    assert.equal(p.run(scripts.inspect), 'password');
    assert.equal(p.run(scripts.password), 'submitted');
    const expectedAccount = /[@\\]/.test(scripts.account) ? scripts.account : 'slai\\' + scripts.account;
    assert.deepEqual(p.submissions, [{username: expectedAccount, password: scripts.secret}]);
  }
  for (const options of [
    {location: 'http://sts.slai.edu.cn/adfs/ls/'},
    {location: 'https://sts.slai.edu.cn.evil.example/adfs/ls/'},
    {location: 'https://sts.slai.edu.cn:444/adfs/ls/'},
    {location: 'https://sts.slai.edu.cn/adfs/oauth2/authorizeevil'},
    {action: 'https://evil.example/steal'},
    {iframe: true}, {captcha: true}, {error: true}, {loading: true}
  ]) {
    const bad = page(options);
    assert.notEqual(bad.run(scripts.username), 'submitted');
    assert.equal(bad.username.value, '');
    assert.equal(bad.password.value, '');
    assert.equal(bad.submissions.length, 0);
  }
  process.stdout.write('School login scripts: two-step payload, resumed password form and credential boundaries passed\n');
});
