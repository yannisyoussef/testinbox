function fn() {
  return {
    baseUrl: java.lang.System.getProperty('testinbox.e2e.baseUrl'),
    apiKey: java.lang.System.getProperty('testinbox.e2e.apiKey'),
    mailDomain: java.lang.System.getProperty('testinbox.e2e.mailDomain'),
  };
}
