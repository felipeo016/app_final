package com.unicanal.contact;

import com.unicanal.channel.ChannelType;

/** Identidad del contacto en un canal: teléfono (WhatsApp) o usuario (Instagram/Facebook). */
public record ContactIdentity(ChannelType channel, String externalId) {
}
