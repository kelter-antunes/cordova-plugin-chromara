var exec = require('cordova/exec');

var Chromara = {
    capturePhoto: function(successCallback, errorCallback) {
        exec(successCallback, errorCallback, 'Chromara', 'capturePhoto', []);
    }
};

module.exports = Chromara;