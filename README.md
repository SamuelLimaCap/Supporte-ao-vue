# Vue Helper — plugin para WebStorm

Oferece autocomplete e navegação para projetos **Vue 2 + JavaScript**:

- `// @form` em mixins
- `this.getInfo.` / `getters.info.`
- `@origem` em props/data/computed, com arrays, callbacks, variáveis, `for...of` e `v-for`
- Ctrl+clique nos campos e na própria anotação `@origem`

Compatível com **WebStorm 2024.2+** (`since-build=242`, sem limite superior).
Verificado com **2024.2.6** (build `242.26775.11`) e **2026.2.3** (build `262.10968.77`).
Requer os plugins **JavaScript** e **Vue**, já incluídos no WebStorm.

---

## Instalação

1. `./gradlew buildPlugin`
2. WebStorm → **Settings → Plugins → ⚙ → Install Plugin from Disk…**
3. Selecione `build/distributions/vue-helper-x.y.z.zip`
4. Reinicie o WebStorm.

Build local: use **JDK 21**. O projeto compila contra o WebStorm 2024.2.6
(`webstorm("2024.2.6")`) para manter compatibilidade com a versão mais antiga suportada.

---

## 1. `// @form X.vue`

Um mixin pode declarar até **3 forms** nas primeiras 10 linhas, um por linha ou separados por
vírgula:

```js
// @form MyForm.vue
// @form OtherForm.vue
// ou: // @form MyForm.vue, OtherForm.vue
// ou: //@form MyForm.vue
// ou: /** @form MyForm.vue */
export default {
  data: () => ({
    registros: [],
  }),
  methods: {
    fechar() {},
  },
};
```

Com o mixin aberto, `this.` completa:

- membros do próprio mixin que o WebStorm já conhece;
- `props`, `data`, `computed` e `methods` dos forms;
- membros dos mixins de cada form, recursivamente;
- métodos aparecem com `()` e o(s) form(s) de origem aparece(m) ao lado
  (`MyForm.vue`, `MyForm.vue, OtherForm.vue`, ...).

Com mais de um form, os membros são unidos. Um campo presente nos dois forms aparece uma única
vez com os dois forms ao lado (`MyForm.vue, OtherForm.vue`). Um campo vindo de um mixin
compartilhado mostra os forms e o arquivo (`MyForm.vue, OtherForm.vue · SharedMixin.js`); se o
mixin só for usado por um form, só esse form aparece.

O mixin aberto não é duplicado pela lista do plugin: o WebStorm já mostra os membros dele.
A resolução do caminho aceita:

```js
// @form MyForm.vue          mesma pasta
// @form ./MyForm.vue       relativo
// @form ../MyForm.vue      relativo
// @form @/views/.../MyForm.vue  a partir de <raiz>/src
```

A raiz é a pasta que tem `jsconfig.json` e `src/`.

O `.vue` do form em si usa o autocomplete nativo do WebStorm; o `@form` serve para os
mixins `.js`. Se o mixin for compartilhado, declare todos os forms nele (até 3).

---

## 2. `this.getInfo.` e `getters.info.`

```js
export default {
  methods: {
    teste() {
      this.getInfo.usuario;
      this.getInfo?.empresa;
      this.$store.getters.info.nome;
      getters.info.email;
    },
  },
};
```

No template:

```html
<span>{{ getInfo.usuario }}</span>
```

Os campos vêm de um índice do projeto (`FileBasedIndex`) montado a partir dos usos de
`getInfo`/`getters.info` nos arquivos `.js` e `.vue`. O índice:

- ignora o campo `do` de URLs como `.../getInfo.do`;
- ignora `node_modules`;
- consulta somente o `src/` do root atual;
- não mostra chave antiga sem arquivo correspondente;
- não precisa de configuração no projeto.

O item aparece com o texto `getInfo.do` ao lado.

---

## 3. `@origem`

Use um comentário antes de uma prop/data/computed para dizer qual objeto define o formato:

```js
props: {
  /** @origem ../SomeMixin.js cadastro.configuracao */
  configuracao: {type: Object, default: () => ({})},

  /** @origem ../Factory.js criarParametros() */
  parametros: {type: Object, default: () => ({})},

  /** @origem ../Factory.js criarItem()[] */
  itens: {type: Array, default: () => []},
}
```

O comentário vale para o próximo membro (até 15 linhas depois, pulando linhas em branco e
comentários). A sintaxe é:

```
@origem <arquivo> <caminho>
@origem <caminho>
```

### 3.1 Arquivo

- nome solto (`Factory.js`): mesma pasta;
- `./x`, `../x`: relativo ao arquivo do comentário;
- `@/x`: `<raiz>/src/x`, onde a raiz tem `jsconfig.json` + `src/`;
- sem extensão: tenta `.js`, `/index.js`, `.vue` e `/index.vue`, nessa ordem;
- **sem arquivo** (uma palavra sem `/` e sem `.js`/`.vue`/`.ts`): o caminho é resolvido
  no próprio arquivo do comentário.

```js
/** @origem ../SomeMixin.js cadastro.configuracao */   // arquivo + caminho
/** @origem ../SomeMixin.js */                         // caminho omitido
/** @origem criarLocal() */                            // mesmo arquivo
/** @origem default */                                 // mesmo arquivo
```

Sem arquivo, `@origem criarLocal()` procura a função `criarLocal` no próprio arquivo.

### 3.2 Caminho

| Caminho | Significado |
|---|---|
| `a.b.c` | procura `a` no `data` do `export default`; se não achar, numa variável/função/import `a` do topo; depois desce `b`, `c` |
| `criarX()` | objeto retornado pela função `criarX` (declaração, arrow ou `return {...}`) |
| `default` | o `export default`; se for função, o objeto retornado |
| `default.a.b` | desce `a` e `b` a partir do export default |
| omitido | usa o nome do próprio membro; se não achar, cai no export default **somente se ele não for um componente** (sem `data`, `methods`, `computed`, `props`, `mixins` ou `components`) |
| `...[]` | o membro é um array; o formato é o do item |

Exemplos:

```js
/** @origem ./Obj.js default */
opcoes: {type: Object, default: () => ({})},
// completa campoA, campoB...

/** @origem ./Nested.js default.nivel1.nivel2 */
nested: {type: Object, default: () => ({})},
// desce nivel1 e nivel2

/** @origem ./SomeMixin.js */
configuracaoSimples: {type: Object, default: () => ({})},
// usa "configuracaoSimples" no data/topo do SomeMixin.js

/** @origem criarLocal() */
local: {type: Object, default: () => ({})},
// função no próprio arquivo
```

Ao resolver valores, o plugin segue:

- parênteses;
- `defineComponent({...})` e `Vue.extend({...})`;
- spread (`{...criarBase(), extra: 1}`);
- nomes, chamadas, variáveis e imports (`import { x } from './x'`, `import x from './x'`).

### 3.3 Arrays

Use `[]` no fim do caminho quando o membro for um array:

```js
/** @origem ../Factory.js criarItem()[] */
itens: {type: Array, default: () => []},
```

- `this.itens.` sozinho **não** lista campos: ficam os métodos de array do WebStorm.
- `this.itens[0].`, `this.itens[i].` e `this.itens.at(0).` listam os campos do item.
- `this.itens.find(item => item.` (ou `findLast`, `findIndex`, `filter`, `forEach`, `map`,
  `flatMap`, `some`, `every`) lista os campos do item no primeiro parâmetro do callback.
- `this.itens.filter(...)[0].` funciona porque `filter`/`slice`/`sort`/`concat`/`reverse`/
  `splice` continuam lista.
- `find`/`findLast`/`at` retornam o item, então
  `this.itens.find(item => item.codigo).` lista os campos do item.
- Se o caminho chegar a um array literal `[{...}]` sem `[]`, o primeiro item é o modelo.
- Array vazio (`[]`) não tem formato: não completa nada.

```js
/** @origem ./Factory.js criarItens() */
itens: {type: Array, default: () => []},
// this.itens[0]. -> campos do primeiro item

/** @origem ./Factory.js criarVazio()[] */
vazios: {type: Array, default: () => []},
// this.vazios[0]. -> nada
```

### 3.4 Variáveis, `for...of` e `v-for`

```js
const item = this.itens[0];
item.                               // campos do item

const item = this.itens.find(i => i.codigo);
item.                               // campos do item

const cfg = this.configuracao;
cfg.                                // campos de configuracao

for (const item of this.itens) {
  item.                             // campos do item
}
```

No template:

```html
<div v-for="item in itens">
  {{ item.codigo }}
</div>

<div v-for="(item, i) in itens">
  {{ item. }}   <!-- campos do item -->
  {{ i. }}      <!-- não completa campos do item -->
</div>

<div v-for="item in itens">
  <div v-for="detalhe in item.detalhes">
    {{ detalhe. }}   <!-- campos do item de item.detalhes -->
  </div>
</div>
```

Regras de sombra:

- um parâmetro de método com o mesmo nome do membro vence e **não** completa `@origem`;
- `catch (e)`, `resp => ...`, `v-slot` e `slot-scope` não são tratados como item de array;
- `for...of` é tratado; `for...in` não;
- o índice do `v-for`/callback não recebe os campos do item.

### 3.5 Onde completa

```js
this.configuracao.            // script / .js
this.configuracao.sub.        // caminho aninhado
```

```html
{{ configuracao. }}           <!-- template -->
{{ configuracao.sub. }}
```

`this.itens.` sozinho continua sendo os métodos de array; para ver os campos do item use
`[0]`, `at(0)` ou um callback (`find`, `filter`, ...).

---

## 4. Ctrl+clique / ir para definição

### Nos campos completados

```js
this.configuracao.campo
item.codigo
```

Ctrl+clique leva à chave do campo no arquivo de origem (o mesmo que aparece em
`arquivo:linha` ao lado do item).

### Na própria anotação `@origem`

```js
/** @origem ../SomeMixin.js cadastro.configuracao */
configuracao: {type: Object, default: () => ({})},
```

- Ctrl+clique em `../SomeMixin.js` → abre o arquivo;
- Ctrl+clique em `cadastro` → vai ao campo `cadastro` no data do mixin;
- Ctrl+clique em `configuracao` → vai ao campo `configuracao` dentro de `cadastro`;
- Ctrl+clique em `default` em `./Obj.js default` → vai ao export default;
- Ctrl+clique em `criarParametros` em `criarParametros()` → vai à função (inclusive
  quando ela vem de um import);
- Ctrl+clique em `default.nivel1.nivel2` → desce cada segmento do caminho.

Funciona em comentários de `.js` e `.vue` e também para caminhos no mesmo arquivo
(`@origem criarLocal()`).

---

## 5. Herança

O mapa de `@origem` é montado, nesta ordem, a partir do arquivo atual, dos forms do
`// @form` e dos mixins deles, recursivamente. O primeiro `@origem` encontrado para um
nome vence.

---

## 6. Compatibilidade

| IDE | Build | Status |
|---|---|---|
| WebStorm 2024.2.6 | `242.26775.11` | Compatible |
| WebStorm 2026.2.3 | `262.10968.77` | Compatible |

`since-build=242`, sem `until-build`: instala em 2024.2 e em versões posteriores. A build
`242.27665.11` é do mesmo branch 242 e também é aceita.

---

## 7. Desenvolvimento

```bash
# testes (2024.2.6)
JAVA_HOME=/caminho/jdk-21 ./gradlew test

# zip instalável
JAVA_HOME=/caminho/jdk-21 ./gradlew buildPlugin

# compatibilidade com 2024.2.6 e o WebStorm atual do snap
JAVA_HOME=/caminho/jdk-21 ./gradlew verifyPlugin
```

Arquivos principais:

- `FormMixinCompletionContributor` — `@form`, `getInfo` e entrada do `@origem`;
- `OrigemResolver` — parser/resolvedor do `@origem`, estados de objeto/lista, `v-for`;
- `OrigemGotoDeclarationHandler` — Ctrl+clique nos campos e na anotação;
- `GetInfoFieldIndex` — índice dos campos de `getInfo`;
- `MixinResolver` — resolução dos forms/mixins e membros.
