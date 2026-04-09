import adapter from '@sveltejs/adapter-static';

/** @type {import('@sveltejs/kit').Config} */
const config = {
  kit: {
    adapter: adapter({
      pages: '../src/main/resources/static/spa',
      assets: '../src/main/resources/static/spa',
      fallback: 'index.html'
    }),
    paths: {
      base: '/app'
    },
    alias: {
      $lib: 'src/lib'
    }
  }
};

export default config;
