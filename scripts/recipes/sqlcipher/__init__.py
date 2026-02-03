from os.path import join, exists
import shutil

from pythonforandroid.recipe import NDKRecipe
from pythonforandroid.util import ensure_dir, current_directory
from pythonforandroid.logger import shprint
import sh


class SqlcipherRecipe(NDKRecipe):
    name = "sqlcipher"
    name = 'sqlcipher'
    version = '4.5.6'
    url = 'https://github.com/sqlcipher/sqlcipher/archive/refs/tags/v{version}.zip'
    generated_libraries = ['sqlcipher']
    depends = ['openssl']

    def should_build(self, arch):
        return not self.has_libs(arch, 'libsqlcipher.so')

    def prebuild_arch(self, arch):
        super().prebuild_arch(arch)
        build_dir = self.get_build_dir(arch.arch)
        with current_directory(build_dir):
            if not exists('sqlite3.c') or not exists('sqlite3.h'):
                if exists('configure'):
                    shprint(sh.Command('./configure'))
                shprint(sh.make, 'sqlite3.c', 'sqlite3.h')

        ensure_dir(join(build_dir, 'jni'))
        shutil.copyfile(join(self.get_recipe_dir(), 'Android.mk'),
                        join(build_dir, 'jni/Android.mk'))

        include_dir = join(build_dir, 'include', 'sqlcipher')
        ensure_dir(include_dir)
        shutil.copyfile(join(build_dir, 'sqlite3.h'), join(include_dir, 'sqlite3.h'))

    def build_arch(self, arch, *extra_args):
        super().build_arch(arch)
        build_dir = self.get_build_dir(arch.arch)
        shutil.copyfile(join(build_dir, 'libs', arch.arch, 'libsqlcipher.so'),
                        join(self.ctx.get_libs_dir(arch.arch), 'libsqlcipher.so'))

    def get_include_dirs(self, arch):
        return [join(self.get_build_dir(arch.arch), 'include')]

    def get_recipe_env(self, arch):
        env = super().get_recipe_env(arch)
        openssl_recipe = self.get_recipe('openssl', self.ctx)
        env['NDK_PROJECT_PATH'] = self.get_build_dir(arch.arch)
        env['OPENSSL_INCLUDE'] = openssl_recipe.get_build_dir(arch.arch) + '/include'
        env['OPENSSL_LIB'] = openssl_recipe.get_build_dir(arch.arch)
        return env


recipe = SqlcipherRecipe()
