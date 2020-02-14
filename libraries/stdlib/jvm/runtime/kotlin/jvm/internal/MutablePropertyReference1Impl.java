/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.jvm.internal;

import kotlin.SinceKotlin;
import kotlin.reflect.KDeclarationContainer;

@SuppressWarnings({"unused"})
public class MutablePropertyReference1Impl extends MutablePropertyReference1 {
    public MutablePropertyReference1Impl(KDeclarationContainer owner, String name, String signature) {
        super(NO_RECEIVER, owner, name, signature);
    }

    @SinceKotlin(version = "1.4")
    public MutablePropertyReference1Impl(Object receiver, KDeclarationContainer owner, String name, String signature) {
        super(receiver, owner, name, signature);
    }

    @Override
    public Object get(Object receiver) {
        return getGetter().call(receiver);
    }

    @Override
    public void set(Object receiver, Object value) {
        getSetter().call(receiver, value);
    }
}
