/* Wires pipe.js, main-behaviour.js and jsPlumb 1.3.16 to the jQuery provided by the
 * jquery3-api plugin.
 *
 * The jquery3-api adjunct exposes jQuery as window.jQuery3 (it calls noConflict(), which only
 * releases `$`). Our scripts use the Q alias, and jsPlumb 1.3.16 expects window.jQuery to be
 * present when it loads. This file runs before both of them.
 *
 * jQuery 3.0 removed .bind()/.unbind(), which jsPlumb calls when attaching connection event
 * listeners.
 */
(function() {
    var jq = (typeof jQuery3 !== 'undefined') ? jQuery3
        : (typeof jQuery !== 'undefined') ? jQuery
        : (typeof Q !== 'undefined' ? Q : null);
    if (!jq) { return; }

    // Q is what pipe.js and main-behaviour.js use, whatever other plugins put on the page.
    window.Q = jq;

    // Ensure jsPlumb can find jQuery via window.jQuery
    if (typeof jQuery === 'undefined') {
        window.jQuery = jq;
    }

    // Restore .bind()/.unbind() removed in jQuery 3.0
    if (typeof jq.fn.bind === 'undefined') {
        jq.fn.bind = function(types, fn) { return this.on(types, fn); };
        jq.fn.unbind = function(types, fn) { return this.off(types, fn); };
    }
})();
