/*
 *  Copyright (c) 2012 Malhar, Inc.
 *  All Rights Reserved.
 */
package com.malhartech.dag;

import com.malhartech.bufferserver.Buffer;

/**
 *
 * @author Chetan Narsude <chetan@malhar-inc.com>
 */
public class EndWindowTuple extends Tuple
{
  private long tupleCount;

  public EndWindowTuple()
  {
    super(null);
    super.setType(Buffer.Data.DataType.END_WINDOW);
  }

  /**
   * @return the tupleCount
   */
  public long getTupleCount()
  {
    return tupleCount;
  }

  /**
   * @param tupleCount the tupleCount to set
   */
  public void setTupleCount(long tupleCount)
  {
    this.tupleCount = tupleCount;
  }

  @Override
  public String toString()
  {
    return "tuples = " + tupleCount + " " + super.toString();
  }
}