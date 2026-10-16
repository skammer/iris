(ns agent.tools.shell-policy
  "Canonical built-in shell policy. Commands with write/exec options require review.
   Explicit user rules can grant additional commands.")

(def default-rules
  (into [{:argv ["pwd"] :decision :allow}]
        (concat
         (map (fn [command] {:argv [command "**"] :decision :allow})
              ["printf" "echo" "which" "type" "ls" "cat" "head" "tail"
               "wc" "df" "uname" "cut" "grep"])
         [{:argv ["rm" "-rf" "/*"] :decision :deny}
          {:argv ["sudo" "rm" "-rf" "/*"] :decision :deny}
          {:argv ["dd" "**"] :decision :deny}
          {:argv ["mkfs" "**"] :decision :deny}
          {:argv ["fdisk" "**"] :decision :deny}
          {:argv ["mkswap" "**"] :decision :deny}])))
