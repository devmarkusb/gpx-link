// Exercise the generated map's event lifecycle without network tiles or a DOM.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const timers = new Map();
let timerId = 0;
function group() {
  return {
    layers: new Set(),
    addLayer(layer) { this.layers.add(layer); return this; },
    removeLayer(layer) { this.layers.delete(layer); return this; },
    addTo(parent) { parent.addLayer(this); return this; },
  };
}
function layer() {
  return {
    addTo(parent) { parent.addLayer(this); return this; },
    on() { return this; },
    bindTooltip(text, options) { this.tooltip = {text, options}; return this; },
  };
}
const bounds = {pad() { return this; }, contains() { return true; }};
const map = Object.assign(group(), {
  on() {}, off() {}, getBounds() { return bounds; },
  getCenter() { return {lat: 0, lng: 0}; }, getZoom() { return 2; },
});
const context = {
  window: {},
  L: {
    map() { return map; }, canvas() { return {}; },
    tileLayer: layer, featureGroup: group, layerGroup: group,
    marker: layer, circleMarker: layer, polyline: layer,
    latLng(lat, lng) { return {lat, lng}; }, divIcon() { return {}; },
    Control: {extend() { return class { addTo() {} }; }},
  },
  setTimeout(fn) { const id = ++timerId; timers.set(id, fn); return id; },
  clearTimeout(id) { timers.delete(id); },
};
const html = fs.readFileSync(0, 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
// Window properties are global identifiers in browsers.
context.window = context;
vm.runInNewContext(script, context);
const payload = {
  markers: [{lat: 1, lon: 2, name: 'Test'}], paths: [], markerLabels: 'off',
};
context.gpxLinkApplyPayload(payload);
context._gpxPoiMoveListener(); // A refresh is queued by the previous payload.
context.gpxLinkApplyPayload({...payload, markerLabels: 'on'});
for (const [id, fn] of [...timers]) {
  timers.delete(id);
  fn();
}
const currentMarker = [...context._gpxPoiLayerGroup.layers][0];
assert.equal(currentMarker.tooltip.options.permanent, true);
