// Karma configuration.
//
// Angular 18's builder works without this file, but this project needs three things its
// defaults do not give: a headless Chrome that tolerates a container's lack of a sandbox, a
// coverage reporter whose output the pipeline can archive as evidence, and — see below — a
// browser with no extensions in it.
module.exports = function (config) {
  config.set({
    basePath: '',
    frameworks: ['jasmine', '@angular-devkit/build-angular'],
    plugins: [
      require('karma-jasmine'),
      require('karma-chrome-launcher'),
      require('karma-jasmine-html-reporter'),
      require('karma-coverage'),
      require('@angular-devkit/build-angular/plugins/karma')
    ],
    jasmine: {
      random: true,
      // A test that only passes in a particular order is not a passing test.
      failSpecWithNoExpectations: true
    },
    reporters: ['progress', 'kjhtml'],
    coverageReporter: {
      dir: require('path').join(__dirname, './coverage'),
      subdir: '.',
      reporters: [{ type: 'html' }, { type: 'lcovonly' }, { type: 'text-summary' }]
    },

    // One browser, used locally and in CI alike. They used to differ — `Chrome` here and
    // `ChromeHeadlessCI` in the pipeline — which meant a green run on a developer's machine
    // said nothing about the browser the build would actually use.
    browsers: ['ChromeHeadlessCI'],
    customLaunchers: {
      ChromeHeadlessCI: {
        base: 'ChromeHeadless',
        flags: [
          '--no-sandbox',
          '--disable-gpu',
          '--disable-dev-shm-usage',

          // These are the important ones, and they were missing.
          //
          // Chrome loads extensions installed by enterprise policy into *every* profile,
          // including the throwaway one Karma creates for a headless run. On this machine that
          // is a UiPath automation extension, which starts a service worker and opens native
          // messaging ports as the page loads. That traffic knocks over Karma's websocket:
          // the server logs "Client disconnected from CONNECTED state (transport error)" and
          // then a fresh connection from the same browser, which Karma reports as
          //
          //     Some of your tests did a full page reload!
          //
          // — with no failing spec, nothing in the browser console, and a non-zero exit. It is
          // timing-dependent, so the suite passed perhaps one run in three and looked like a
          // flake in the application code. It is not: a pure-logic spec with no DOM triggers
          // it just as readily.
          //
          // A test browser should have nothing in it but the test. Whatever an organisation
          // installs into the developer's browser is not part of this application, and it has
          // no business being loaded while the suite runs.
          '--disable-extensions',
          '--disable-component-extensions-with-background-pages',
          '--disable-background-networking',
          '--disable-default-apps',
          '--disable-sync'
        ]
      }
    },
    restartOnFileChange: true
  });
};
