/*
 * LineNumberTableAttribute.java
 *
 * Copyright (c) 2013 Mike Strobel
 *
 * This source code is subject to terms and conditions of the Apache License, Version 2.0.
 * A copy of the license can be found in the License.html file at the root of this distribution.
 * By using this source code in any fashion, you are agreeing to be bound by the terms of the
 * Apache License, Version 2.0.
 *
 * You must not remove this notice, or any other, from this software.
 */

package com.strobel.assembler.ir.attributes;

import com.strobel.core.ArrayUtilities;
import com.strobel.core.VerifyArgument;

import java.util.List;

/**
 * @author Mike Strobel
 */
public final class LineNumberTableAttribute extends SourceAttribute {
    private final List<LineNumberTableEntry> _entries;

    public LineNumberTableAttribute(final LineNumberTableEntry[] entries) {
        super(AttributeNames.LineNumberTable, 2 + (VerifyArgument.notNull(entries, "entries").length * 4));
        _entries = ArrayUtilities.asUnmodifiableList(entries.clone());
    }

    public List<LineNumberTableEntry> getEntries() {
        return _entries;
    }
}