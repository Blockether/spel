## PATCH http://localhost:35469/echo → 200 OK

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
date: Mon, 20 Jul 2026 09:17:09 GMT
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
curl 'http://localhost:35469/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```
