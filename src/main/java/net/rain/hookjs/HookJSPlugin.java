package net.rain.hookjs;

import dev.latvian.mods.kubejs.KubeJSPlugin;
import dev.latvian.mods.kubejs.script.BindingsEvent;

public class HookJSPlugin extends KubeJSPlugin {

    @Override
    public void registerBindings(BindingsEvent event) {
        event.add("HookCoreJS", net.rain.hookjs.core.HookCore.getInstance());
    }

}
