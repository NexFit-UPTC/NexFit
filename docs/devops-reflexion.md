1\. ¿Qué problema concreto evita un pipeline de CI cuando varias personas trabajan sobre el mismo código?



Evita que el trabajo de una persona rompa el de otra sin que nadie se entere. Sin CI, cada quien prueba solo en su máquina, y un código que “a mí me funciona” puede fallar al mezclarse con develop. En NexFit, ci.yml corre en cada Pull Request, y las reglas de protección de develop y main exigen que los checks backend y frontend estén en verde antes del Squash and merge.



2\. ¿Qué error detectó el check en el Paso 3 y cómo lo habrían descubierto sin automatización?



En el PR #4 (rama santiagomunevar/prueba-proteccion-develop) introdujimos a propósito un error de lint en frontend/src/app/app.routes.ts. El job frontend falló en el paso npm run lint con dos errores de ESLint: la variable sinUsar se declaraba y no se usaba (@typescript-eslint/no-unused-vars) y se usaba el tipo any (@typescript-eslint/no-explicit-any). El job backend pasó en verde, y el ruleset de develop impidió fusionar mientras frontend estuviera en rojo.



Sin automatización, ese error solo se habría encontrado si alguien ejecutaba npm run lint a mano antes de fusionar, o si un compañero lo notaba al revisar el código. Como el build de Angular compila con ese código sin quejarse, el error habría llegado a develop sin que nadie lo viera, y se habría descubierto tarde, cuando otra persona trajera los cambios o el código ya estuviera mezclado con más trabajo. Con el CI, el fallo aparece en 13 segundos, dentro del propio PR y antes de fusionar.



3\. ¿A qué escenario de calidad de la Semana 6 ayuda este pipeline y por qué?



Principalmente al **E4 · Mantenibilidad (RNF-05)**. Su medida es “0 dependencias indebidas entre módulos y al menos 1 prueba automática por regla de negocio con mecanismo en el backend”. Esa medida solo se verifica si algo la ejecuta en cada cambio.



Ayuda también al **E1 · Seguridad (RNF-01):** las pruebas de autorización y aislamiento corren en cada PR, y la suite de la NEX-28 se apoyará en eso. El E5 (confiabilidad) lo cubre otro workflow, backup.yml, que no forma parte del CI.



4.¿Qué no valida hoy su pipeline?

**Pruebas del frontend:** el job frontend hace npm ci, lint y build, pero no ejecuta ninguna prueba. Solo garantiza que compila y cumple el estilo, no que funcione.

**Despliegue:** el CI no despliega ni hace una prueba de humo sobre el entorno real. En despliegue.md consta que el despliegue del backend en Azure es manual con CLI y que no hay redespliegue automático del frontend en Vercel.



5\. ¿Qué agregarían primero: pruebas automatizadas, análisis de código o despliegue automático?



Más pruebas automatizadas, empezando por el frontend, que hoy no tiene ninguna. Es la red de seguridad de todo lo demás. Un despliegue automático sin pruebas suficientes publicaría errores más rápido, no mejor, mientras que el backend ya tiene una suite fuerte el frontend solo se compila. Ahí el riesgo es mayor.

