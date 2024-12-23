var exec = require('cordova/exec');

var Chromara = {
    showPreview: function(success, error) {
        exec(success, error, 'Chromara', 'showPreview', []);
    },
    removePreview: function(success, error) {
        exec(success, error, 'Chromara', 'removePreview', []);
    },
    capturePhoto: function(success, error) {
        exec(success, error, 'Chromara', 'capturePhoto', []);
    }
};

module.exports = Chromara;