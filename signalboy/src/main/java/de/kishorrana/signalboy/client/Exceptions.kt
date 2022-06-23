package de.kishorrana.signalboy.client

// Exceptions thrown during connection-attempt:
class NoConnectionAttemptsLeftException : Exception()

// Exceptions thrown after a connection has been established:
class ConnectionTimeoutException : Exception()
