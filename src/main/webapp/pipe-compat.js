/* Compatibility for jsPlumb 1.3.16 with newer Jenkins/jQuery.
 *
 * Newer Jenkins exposes jQuery as window.Q (via noConflict) but not always as
 * window.jQuery. jsPlumb 1.3.16 expects window.jQuery to be present when it
 * loads. This file runs before jsPlumb and ensures jQuery is accessible.
 *
 * Additionally, jQuery 3.0 removed .bind()/.unbind() which jsPlumb calls
 * when attaching connection event listeners.
 */
(function() {
    var jq = (typeof jQuery !== 'undefined') ? jQuery : (typeof Q !== 'undefined' ? Q : null);
    if (!jq) { return; }

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
