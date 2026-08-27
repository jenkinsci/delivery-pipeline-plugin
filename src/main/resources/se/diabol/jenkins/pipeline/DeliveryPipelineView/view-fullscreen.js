document.addEventListener('DOMContentLoaded', function() {

    const configSpan = document.querySelector(".fullscreen-data-holder");
    const crumbRequestField = configSpan.dataset.crumbField;
    const crumbValue = configSpan.dataset.crumbValue;
    var isRunAsTest = configSpan.dataset.runAsTest === 'true';
    var rootURL = configSpan.dataset.rootUrl;
    var resURL = configSpan.dataset.resUrl;

    window.isRunAsTest = isRunAsTest;
    window.rootURL = rootURL;
    window.resURL = resURL;

    crumb.init(crumbRequestField, crumbValue);

    var Q = jQuery.noConflict();
});
