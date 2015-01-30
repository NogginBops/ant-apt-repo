# ant-apt-repo
An Ant plugin to create apt repository from a Debian/Ubuntu development project, derived from the maven plugin which does the same - [theoweiss/apt-repo](https://github.com/theoweiss/apt-repo)

The Ant task is usually used with the debian package creation task [ant-deb](http://code.google.com/p/ant-deb-task/).

## Usage

'''
	<taskdef name="aptrepo" classname="com.codemarvels.ant.aptrepotask.AptRepoTask" >
		<classpath>
			<fileset dir="lib/ant/apt-repo" ><!--all jar files in this project is expected to be found here -->
				 <include name="*.jar"/>
			</fileset>
		</classpath>
	</taskdef>
<target name="createRepository">
  <aptrepo repoDir="${debian.folder}/output/repository"/> <!-- keep all .deb files in this folder -->
</target>

'''
## Note
* Keep all .deb files you want to publish in the *repoDir* folder. The package listings will be created in the same folder, which can then be published using a web-server as debian repository.

