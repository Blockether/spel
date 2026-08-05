## DELETE http://localhost:45853/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Wed, 05 Aug 2026 07:16:25 GMT
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
curl 'http://localhost:45853/echo' \
  -X DELETE
```
