## DELETE http://localhost:40401/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Mon, 10 Aug 2026 08:48:53 GMT
```

### Response Body
```json
{
  "method": "DELETE",
  "path": "/echo"
}
```

### cURL
```bash
curl 'http://localhost:40401/echo' \
  -X DELETE
```
