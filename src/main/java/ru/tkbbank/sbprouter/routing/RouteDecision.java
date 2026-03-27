package ru.tkbbank.sbprouter.routing;

public record RouteDecision(String upstreamName, TerminalOwner terminalOwner, String requestType) {}
