## PUT http://localhost:33695/echo → 200 OK

### Request Body
```json
{
  "name": "Eve Updated"
}
```

### Response Headers
```
content-length: 61
content-type: application/json
date: Mon, 27 Jul 2026 03:16:07 GMT
```

### Response Body
```json
{
  "method": "PUT",
  "path": "/echo",
  "body": {
    "name": "Eve Updated"
  }
}
```

### cURL
```bash
curl 'http://localhost:33695/echo' \
  -X PUT \
  -d '{
  "name": "Eve Updated"
}'
```
