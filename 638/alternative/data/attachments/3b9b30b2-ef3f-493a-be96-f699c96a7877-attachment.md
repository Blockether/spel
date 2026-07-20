## PUT http://localhost:36159/echo → 200 OK

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
date: Mon, 20 Jul 2026 09:30:49 GMT
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
curl 'http://localhost:36159/echo' \
  -X PUT \
  -d '{
  "name": "Eve Updated"
}'
```
