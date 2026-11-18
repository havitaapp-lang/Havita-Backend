package com.havita.modules.assembly.api.dto;

/**
 * Fase 2 / P6: un poder de la asamblea relacionado con el usuario autenticado. {@code relation} es GRANTOR (lo otorgo
 * siendo hoy propietario activo de la unidad) o PROXY (es su apoderado con cuenta).
 */
public record MyAssemblyProxyResponse(String relation, AssemblyProxyResponse proxy) {}
