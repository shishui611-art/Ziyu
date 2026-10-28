// Exercise the exact Java-embedded script without injecting into any app.
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');
const root = path.resolve(__dirname, '..');
const java = fs.readFileSync(path.join(root, 'android-app/app/src/main/java/io/github/xgl34222220/ziyu/xposed/WebFontHook.java'), 'utf8');
const block = java.split('private static final String SCRIPT =')[1].split(';\n\n    private static void watch')[0];
const script = [...block.matchAll(/"(?:\\.|[^"\\])*"/g)].map(match => JSON.parse(match[0])).join('');
class Element {
  constructor(tag, cls = '') { this.nodeType = 1; this.tagName = tag; this.className = cls; this.parentElement = null; this.children = []; this.attrs = {}; this.style = {values: {}, setProperty(k,v,p) {this.values[k] = [v,p];}}; }
  append(node) { node.parentElement = this; this.children.push(node); return node; }
  getAttribute(name) { return this.attrs[name] ?? null; }
  hasAttribute(name) { return name in this.attrs; }
  setAttribute(name,value) { this.attrs[name] = value; }
  get textContent() {return this.children.map(n => n.nodeType === 3 ? n.nodeValue : n.textContent).join('');}
}
class Text { constructor(s) { this.nodeType = 3; this.nodeValue = s; this.parentElement = null; } }
const html = new Element('HTML');
const body = html.append(new Element('BODY'));
const text = body.append(new Element('P')); text.append(new Text('你好 world'));
const icon = body.append(new Element('SPAN','fa fa-user')); icon.append(new Text('home'));
const pua = body.append(new Element('SPAN')); pua.append(new Text('\ue001home'));
const code = body.append(new Element('CODE')); code.append(new Text('demo'));
const hidden = body.append(new Element('SPAN')); hidden.setAttribute('aria-hidden','true'); hidden.append(new Text('secret'));
const mixed = body.append(new Element('P')); mixed.append(new Text('Hello ')); mixed.append(new Element('SPAN')).append(new Text('\ue001'));
let observer; const frames = [];
const document = {documentElement: html, createTreeWalker(element) {
  const nodes=[]; function visit(node) { if(node.nodeType===3) nodes.push(node); else for(const child of node.children) visit(child); }
  visit(element); return {nextNode() {return nodes.shift() || null;}};
}};
class MutationObserver { constructor(callback) {observer = callback;} observe() {} }
const window = {requestAnimationFrame(callback) {frames.push(callback);}};
const context = {window, document, MutationObserver, NodeFilter:{SHOW_TEXT:4}, setTimeout};
function flush() { let limit=10000; while(frames.length && limit--) frames.shift()(); assert(limit>0, 'frame queue did not settle'); }
vm.runInNewContext(script, context); flush();
assert.deepStrictEqual(text.style.values['font-family'], ['sans-serif','important']);
for(const e of [icon,pua,code,hidden,mixed]) assert.strictEqual(e.style.values['font-family'], undefined);
const added=[];
for(let i=0;i<300;i++) { const e=body.append(new Element('P')); e.append(new Text('new ' + i)); added.push(e); }
observer([{type:'childList',addedNodes:added}]); flush();
assert(added.every(e=>e.style.values['font-family']?.[0] === 'sans-serif'));
vm.runInNewContext(script, context); flush();
console.log('webfont_hook_test: PASS');
