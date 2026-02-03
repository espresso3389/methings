from pythonforandroid.recipe import PythonRecipe


class PySqlcipher3Recipe(PythonRecipe):
    name = "pysqlcipher3"
    version = '1.2.0'
    url = 'https://files.pythonhosted.org/packages/source/p/pysqlcipher3/pysqlcipher3-{version}.tar.gz'
    depends = ['sqlcipher', 'setuptools']
    call_hostpython_via_targetpython = False

    def get_recipe_env(self, arch, with_flags_in_cc=True):
        env = super().get_recipe_env(arch, with_flags_in_cc)
        sqlcipher_recipe = self.get_recipe('sqlcipher', self.ctx)
        include_dir = sqlcipher_recipe.get_include_dirs(arch)[0]
        env['CFLAGS'] += f' -I{include_dir}'
        env['LDFLAGS'] += f' -L{self.ctx.get_libs_dir(arch.arch)}'
        env['LIBS'] = env.get('LIBS', '') + ' -lsqlcipher'
        return env


recipe = PySqlcipher3Recipe()
