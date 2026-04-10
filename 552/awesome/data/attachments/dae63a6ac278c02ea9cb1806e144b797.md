## PATCH http://localhost:40219/echo → 200 OK

### Request Headers
```
PATCH http://localhost:40219/echo
```

### Request Body
```json
{
  "email": "alice3@example.org"
}
```

### Response Headers
```
content-length: 71
content-type: application/json
date: Fri, 10 Apr 2026 22:28:59 GMT
```

### Response Body
```json
{
  "method": "PATCH",
  "path": "/echo",
  "body": {
    "email": "alice3@example.org"
  }
}
```

### cURL
```bash
curl 'http://localhost:40219/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```
