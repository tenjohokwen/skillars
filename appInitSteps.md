# Intro

* This document is meant to guide the creation of a new project using this as a template

## Step 1

1.1 Create your new project folder (e.g skillars)
1.2 Copy recursively all the contents unto skillars


## Step 2 

2.1 Give the prompt to change package names and occurrences of the template name to skillars


## Step 3

3.1 copy .gitignore from template
3.2 Handle the frontend issue

```
  rm -rf  src/frontend/node_modules                                                                                                                                                                                                                                                                             
  cd src/frontend && npm install 
                                                                                                                                                                                                                                                                                                                       
  Or if running from inside the src/frontend directory:
                                                                                                                                                                                                                                                                                                                       
  rm -rf node_modules
  npm install
```

## Step 4

4.1 In src/frontend run "npx quasar build" to ensure the frontend builds.

4.2 In the root folder of skillars, run `mvn verify` to ensure build works
