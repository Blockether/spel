## PATCH http://localhost:34233/echo → 200 OK

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
date: Mon, 13 Apr 2026 02:46:43 GMT
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
curl 'http://localhost:34233/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```
