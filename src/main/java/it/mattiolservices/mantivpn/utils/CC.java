package it.mattiolservices.mantivpn.utils;

import net.kyori.adventure.text.Component;

public class CC {

    public static Component translate(String s){
        return Component.text(s.replace("&","§"));
    }
}
