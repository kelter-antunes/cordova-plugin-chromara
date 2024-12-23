var exec = require('cordova/exec');

var Chromara = {
    capturePhoto: function(successCallback, errorCallback) {
        exec(successCallback, errorCallback, 'Chromara', 'capturePhoto', []);
    },
    removePreview: function(successCallback, errorCallback) {
        exec(successCallback, errorCallback, 'Chromara', 'removePreview', []);
    }
};

module.exports = Chromara;