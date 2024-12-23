var exec = require('cordova/exec');

var Chromara = {
    capturePhoto: function(successCallback, errorCallback) {
        exec(
            function(result) {
                // Optionally handle success result
                if (successCallback) successCallback(result);
            },
            function(error) {
                if (errorCallback) errorCallback(error);
            },
            'chromara', // Ensure plugin name matches the one registered in plugin.xml
            'capturePhoto',
            []
        );
    },
    removePreview: function(successCallback, errorCallback) {
        exec(
            function(result) {
                // Optionally handle success result
                if (successCallback) successCallback(result);
            },
            function(error) {
                if (errorCallback) errorCallback(error);
            },
            'chromara',
            'removePreview',
            []
        );
    }
};

module.exports = Chromara;