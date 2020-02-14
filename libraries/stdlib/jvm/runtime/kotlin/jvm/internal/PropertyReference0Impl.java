/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.jvm.internal;

import kotlin.SinceKotlin;
import kotlin.reflect.KDeclarationContainer;

@SuppressWarnings({"unused"})
public class PropertyReference0Impl extends PropertyReference0 {
    public PropertyReference0Impl(KDeclarationContainer owner, String name, String signature) {
        super(NO_RECEIVER, owner, name, signature);
    }

    @SinceKotlin(version = "1.4")
    public PropertyReference0Impl(Object receiver, KDeclarationContainer owner, String name, String signature) {
        super(receiver, owner, name, signature);
    }

    @Override
    public Object get() {
        return getGetter().call();
    }
}
