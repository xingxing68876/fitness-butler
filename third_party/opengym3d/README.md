# OpenGym3D Exercise Pack — 24 Animated Gym Exercises (GLB)

24 rigged, animated 3D exercise loops in glTF binary (.glb) format, ready to
drop into any engine or web viewer that speaks glTF — Three.js, Babylon.js,
Unity (glTFast/UnityGLTF), Godot, Unreal (glTF importer), React Three Fiber,
or a plain `<model-viewer>` tag.

Live demo of every animation: https://assiamahs.github.io/opengym3d/

## What's inside

```
models/   24 animated .glb files (one exercise per file, looping clip baked in)
thumbs/   24 matching PNG thumbnails
poses/    24 exercise definition JSONs (keyframe poses, tempo, joint targets)
exercises.json  master manifest: name, equipment, difficulty, primary/secondary
                muscles, step-by-step form cues per exercise
```

## Exercise list

Bent-Over Row · Bicep Curl · Calf Raise · Crunch · Deadlift · Dumbbell
Shoulder Press · Front Raise · Glute Bridge · Good Morning · Hammer Curl ·
High Knees · Jumping Jack · Lateral Raise · Lunge · Overhead Press · Overhead
Tricep Extension · Plank · Push-Up · Reverse Lunge · Romanian Deadlift ·
Squat · Sumo Squat · Superman · Wall Sit

## Quick start (Three.js)

```js
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';
const gltf = await new GLTFLoader().loadAsync('models/squat.glb');
scene.add(gltf.scene);
const mixer = new THREE.AnimationMixer(gltf.scene);
mixer.clipAction(gltf.animations[0]).play();
// call mixer.update(delta) in your render loop
```

## Quick start (Unity)

Import via [glTFast](https://github.com/atteneder/glTFast) (free, official).
Each file carries one looping AnimationClip — set it to Loop in the importer
and drive it from an Animator or play it directly.

## Manifest fields

Every entry in `exercises.json`:
`id`, `name`, `equipment`, `difficulty`, `primary` / `secondary` muscle
groups, `steps` (form cues, ready for tooltips or coaching UI), `glb`,
`thumb`.

## License

MIT — use in commercial apps, games, and sites. Attribution appreciated, not
required. Figure generated with MPFB2; animations authored from the pose
definitions in `poses/`.
