/*
 *
 *  Copyright (c) 2015 University of Massachusetts
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you
 *  may not use this file except in compliance with the License. You
 *  may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 *
 *  Initial developer(s): Westy, Emmanuel Cecchet
 *
 */
package edu.umass.cs.gnsclient.console.commands;

import java.io.File;
import java.io.FileOutputStream;
import java.io.ObjectOutputStream;
import java.util.StringTokenizer;

import edu.umass.cs.gnsclient.client.util.GuidEntry;
import edu.umass.cs.gnsclient.client.util.KeyPairUtils;
import edu.umass.cs.gnsclient.console.ConsoleModule;

/**
 * Command that saves GUID/alias/Keypair information to a file
 * 
 * @author <a href="mailto:cecchet@cs.umass.edu">Emmanuel Cecchet </a>
 * @version 1.0
 */
public class GuidExport extends ConsoleCommand
{

  /**
   * Creates a new <code>GuidExport</code> object
   * 
   * @param module
   */
  public GuidExport(ConsoleModule module)
  {
    super(module);
  }

  @Override
  public String getCommandDescription()
  {
    return "Saves alias/GUID and keypair information into a file on disk (careful, the file is not encrypted)";
  }

  @Override
  public String getCommandName()
  {
    return "guid_export";
  }

  @Override
  public String getCommandParameters()
  {
    return "alias path_and_filename";
  }

  /**
   * Override execute to not check for existing connectivity
   * @throws java.lang.Exception
   */
  @Override
  public void execute(String commandText) throws Exception
  {
    parse(commandText);
  }

  @Override
  public void parse(String commandText) throws Exception
  {
    try
    {
      StringTokenizer st = new StringTokenizer(commandText.trim());
      if (st.countTokens() != 2)
      {
        console.printString("Wrong number of arguments for this command.\n");
        return;
      }
      String aliasName = st.nextToken();
      String filename = st.nextToken();

      if (!module.isSilent())
        console.printString("Looking up alias " + aliasName + " GUID and certificates...\n");
      GuidEntry myGuid = KeyPairUtils.getGuidEntry(module.getGnsInstance(), aliasName);

      if (myGuid == null)
      {
        console.printString("You do not have the private key for alias " + aliasName);
        console.printNewline();
        return;
      }

      File f = new File(filename);
      f.createNewFile();
      ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(f));
      myGuid.writeObject(oos);
      oos.flush();
      oos.close();
      console.printString("Keys for " + aliasName + " stored in " + filename);
      console.printNewline();
    }
    catch (Exception e)
    {
      e.printStackTrace();
      console.printString("Failed to save keys ( " + e + ")\n");
    }
  }
}
