## DELETE http://localhost:35453/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Fri, 17 Jul 2026 19:39:18 GMT
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
curl 'http://localhost:35453/echo' \
  -X DELETE
```
