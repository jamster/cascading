/*
 * Copyright (c) 2007-2010 Concurrent, Inc. All Rights Reserved.
 *
 * Project and contact information: http://www.cascading.org/
 *
 * This file is part of the Cascading project.
 *
 * Cascading is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Cascading is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Cascading.  If not, see <http://www.gnu.org/licenses/>.
 */

package cascading.pipe.cogroup;

import java.util.Iterator;

import cascading.flow.FlowProcess;
import cascading.flow.hadoop.HadoopFlowProcess;
import cascading.tuple.Fields;
import cascading.tuple.IndexTuple;
import cascading.tuple.SpillableTupleList;
import cascading.tuple.Tuple;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.log4j.Logger;

/**
 * Class CoGroupClosure is used internally to represent co-grouping results of multiple tuple streams.
 * <p/>
 * <p/>
 * "org.apache.hadoop.io.compress.LzoCodec,org.apache.hadoop.io.compress.GzipCodec,org.apache.hadoop.io.compress.DefaultCodec"
 */
public class CoGroupClosure extends GroupClosure
  {
  public static final String SPILL_THRESHOLD = "cascading.cogroup.spill.threshold";
  private static final int defaultThreshold = 10 * 1000;

  public static final String SPILL_COMPRESS = "cascading.cogroup.spill.compress";

  public static final String SPILL_CODECS = "cascading.cogroup.spill.codecs";
  private static final String defaultCodecs = "org.apache.hadoop.io.compress.GzipCodec,org.apache.hadoop.io.compress.DefaultCodec";

  /** Field LOG */
  private static final Logger LOG = Logger.getLogger( CoGroupClosure.class );

  /** Field groups */
  SpillableTupleList[] groups;
  private int numSelfJoins;
  private CompressionCodec codec;
  private long threshold;
  private JobConf conf;

  public CoGroupClosure( FlowProcess flowProcess, int numSelfJoins, Fields[] groupingFields, Fields[] valueFields )
    {
    super( groupingFields, valueFields );
    this.numSelfJoins = numSelfJoins;
    this.codec = getCompressionCodec( flowProcess );
    this.threshold = getLong( flowProcess, SPILL_THRESHOLD, defaultThreshold );
    this.conf = ( (HadoopFlowProcess) flowProcess ).getJobConf();

    initLists( flowProcess );
    }

  @Override
  public int size()
    {
    return groups.length;
    }

  @Override
  public Iterator<Tuple> getIterator( int pos )
    {
    if( pos < 0 || pos >= groups.length )
      throw new IllegalArgumentException( "invalid group position: " + pos );

    return makeIterator( pos, groups[ pos ].iterator() );
    }

  public boolean isEmpty( int pos )
    {
    return groups[ pos ].isEmpty();
    }

  @Override
  public void reset( Joiner joiner, Tuple grouping, Iterator values )
    {
    super.reset( joiner, grouping, values );

    build();
    }

  private void build()
    {
    clearGroups();

    while( values.hasNext() )
      {
      IndexTuple current = (IndexTuple) values.next();
      int pos = current.getIndex();

      // if this is the first (lhs) co-group, just use values iterator
      if( numSelfJoins == 0 && pos == 0 )
        {
        groups[ pos ].setIterator( current, values );
        break;
        }

      boolean spilled = groups[ pos ].add( (Tuple) current.getTuple() ); // get the value tuple for this cogroup

      if( spilled && ( groups[ pos ].getNumFiles() - 1 ) % 10 == 0 )
        {
        LOG.info( "spilled group: " + groupingFields[ pos ].printVerbose() + ", on grouping: " + getGrouping().print() );

        Runtime runtime = Runtime.getRuntime();
        long freeMem = runtime.freeMemory() / 1024 / 1024;
        long maxMem = runtime.maxMemory() / 1024 / 1024;
        long totalMem = runtime.totalMemory() / 1024 / 1024;

        LOG.info( "mem on spill (mb), free: " + freeMem + ", total: " + totalMem + ", max: " + maxMem );
        }
      }
    }

  private void clearGroups()
    {
    for( SpillableTupleList group : groups )
      group.clear();
    }

  private void initLists( FlowProcess flowProcess )
    {
    int numPipes = groupingFields.length;
    groups = new SpillableTupleList[Math.max( numPipes, numSelfJoins + 1 )];

    for( int i = 0; i < numPipes; i++ ) // use numPipes not numSelfJoins, see below
      groups[ i ] = new SpillableTupleList( threshold, conf, codec, flowProcess );

    for( int i = 1; i < numSelfJoins + 1; i++ )
      groups[ i ] = groups[ 0 ];
    }

  private long getLong( FlowProcess flowProcess, String key, long defaultValue )
    {
    String value = (String) flowProcess.getProperty( key );

    if( value == null || value.length() == 0 )
      return defaultValue;

    return Long.parseLong( value );
    }

  public CompressionCodec getCompressionCodec( FlowProcess flowProcess )
    {
    String compress = (String) flowProcess.getProperty( SPILL_COMPRESS );

    if( compress != null && !Boolean.parseBoolean( compress ) )
      return null;

    String codecs = (String) flowProcess.getProperty( SPILL_CODECS );

    if( codecs == null || codecs.length() == 0 )
      codecs = defaultCodecs;

    Class<? extends CompressionCodec> codecClass = null;

    for( String codec : codecs.split( "[,\\s]+" ) )
      {
      try
        {
        LOG.info( "attempting to load codec: " + codec );
        codecClass = Thread.currentThread().getContextClassLoader().loadClass( codec ).asSubclass( CompressionCodec.class );

        if( codecClass != null )
          {
          LOG.info( "found codec: " + codec );

          break;
          }
        }
      catch( ClassNotFoundException exception )
        {
        // do nothing
        }
      }

    if( codecClass == null )
      {
      LOG.warn( "codecs set, but unable to load any: " + codecs );
      return null;
      }

    return ReflectionUtils.newInstance( codecClass, ( (HadoopFlowProcess) flowProcess ).getJobConf() );
    }
  }
