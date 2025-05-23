---
navigation:
  title: "Modular Router Overview"
  icon: "modularrouters:modular_router"
  parent: modularrouters:intro.md
---

# Modular Router Overview

The *Modular Router* is the central block of the routing system.  By itself, it does nothing other than serve as a one-slot inventory (which can be piped into and out of by hoppers and other mod piping systems).

To do anything useful with a router, however, you need to install one or more (up to nine) [Modules](./modules.md).

Every time a router ticks - once per second by default - it will execute every installed module, in order.  Each of these modules will operate on the item(s) in the buffer or on the world around the router in a specific way - see the **Modules** section for info on each individual module type.

The router's operation can also be modified with **Upgrades** - speed it up, let it handle more items per tick, increase the range of certain modules...

